package dev.diff2test.android.cli

import dev.diff2test.android.changedetector.GitDiffChangeDetector
import dev.diff2test.android.changedetector.extractKotlinSymbols
import dev.diff2test.android.contextbuilder.DefaultTestContextBuilder
import dev.diff2test.android.core.ChangeSet
import dev.diff2test.android.core.ChangeSource
import dev.diff2test.android.core.ChangedFile
import dev.diff2test.android.core.ChangedSymbol
import dev.diff2test.android.core.GeneratedTestBundle
import dev.diff2test.android.core.GradleRunRequest
import dev.diff2test.android.core.RiskLevel
import dev.diff2test.android.core.SymbolKind
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.core.TestType
import dev.diff2test.android.core.ViewModelAnalysis
import dev.diff2test.android.gradlerunner.JvmGradleRunner
import dev.diff2test.android.kotlinanalyzer.SourceBackedViewModelAnalyzer
import dev.diff2test.android.policy.DefaultPolicyEngine
import dev.diff2test.android.styleindex.DefaultStyleIndexer
import dev.diff2test.android.testclassifier.DefaultTestClassifier
import dev.diff2test.android.testgenerator.AiFailureMode
import dev.diff2test.android.testgenerator.FileSystemGeneratedTestWriter
import dev.diff2test.android.testgenerator.KotlinUnitTestGenerator
import dev.diff2test.android.testgenerator.ResponsesApiTestGenerator
import dev.diff2test.android.testgenerator.inferModuleRootFromTarget
import dev.diff2test.android.testgenerator.responsesApiConfigFromEnvironment
import dev.diff2test.android.testplanner.DefaultTestPlanner
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    when (val command = args.firstOrNull()) {
        "init" -> runInit(parseInitArguments(args.drop(1)))
        "doctor" -> runDoctor()
        "scan" -> runScan()
        "plan" -> runPlan(args.getOrNull(1))
        "generate" -> runGenerate(parseGenerateArguments(args.drop(1)))
        "auto" -> runAuto(parseAutoArguments(args.drop(1)))
        "verify" -> runVerify(args.getOrNull(1))
        null, "help", "--help", "-h" -> printHelp()
        else -> {
            println("Unknown command: $command")
            printHelp()
        }
    }
}

private val workspaceRoot: Path = findWorkspaceRoot(Path.of(System.getProperty("user.dir")))

private fun runInit(options: InitOptions) {
    val configPath = defaultConfigPath()
    if (Files.exists(configPath) && !options.force) {
        println("Config already exists: $configPath")
        println("Use `./d2t init --force` to overwrite it.")
        return
    }

    Files.createDirectories(configPath.parent)
    Files.writeString(configPath, defaultConfigTemplate())
    println("Wrote config template to $configPath")
}

private fun runDoctor() {
    val loadResult = loadConfig()
    val resolved = resolveAiConfiguration(loadResult, System.getenv())
    println(renderDoctorReport(loadResult, resolved))
}

private fun runScan() {
    val changeSet = GitDiffChangeDetector().scan(
        request = dev.diff2test.android.changedetector.ScanRequest(
            workingDirectory = workspaceRoot,
        ),
    )
    println("Source: ${changeSet.source}")
    println("Base: ${changeSet.baseRef}")
    println("Head: ${changeSet.headRef ?: "(working tree)"}")
    println("Summary: ${changeSet.summary}")

    if (changeSet.files.isNotEmpty()) {
        println("Files:")
        changeSet.files.forEach { file ->
            println("- ${file.path}")
            println("  hunks: ${file.hunks.size}")
            if (file.changedSymbols.isNotEmpty()) {
                val symbols = file.changedSymbols.joinToString { "${it.kind}:${it.name}" }
                println("  symbols: $symbols")
            }
        }
    }
}

private fun runPlan(target: String?) {
    val analysis = resolveAnalysis(target)
    val plan = createPlan(analysis)
    println(renderPlan(plan))
    printAnalysisWarnings(analysis)
}

private fun runGenerate(options: GenerateOptions) {
    val analysis = resolveAnalysis(options.target)
    val generator = createGenerator(options.aiPreference, options.model, options.strictAi)
    printAnalysisWarnings(analysis)
    val bundle = createBundle(analysis, generator)

    if (options.write) {
        writeGeneratedFiles(bundle, analysis.filePath, options.outputRoot)
    } else {
        printPreview(bundle)
    }
}

private fun runAuto(options: AutoOptions) {
    val analyses = resolveChangedAnalyses()
    check(analyses.isNotEmpty()) {
        "No changed ViewModel files were detected in the current diff."
    }
    val generator = createGenerator(options.aiPreference, options.model, options.strictAi)
    val verificationFailures = mutableListOf<String>()

    analyses.forEach { analysis ->
        println("Generating tests for ${analysis.filePath}")
        printAnalysisWarnings(analysis)
        val bundle = createBundle(analysis, generator)
        writeGeneratedFiles(bundle, analysis.filePath, outputRootOverride = null)

        if (options.verifyAfterGenerate) {
            val verification = verifyGeneratedBundle(
                analysis = analysis,
                bundle = bundle,
                attemptRepair = options.repairOnFailure,
            )
            printGeneratedVerification(verification)
            if (verification.finalResult.status != dev.diff2test.android.core.ExecutionStatus.PASSED) {
                verificationFailures += "${analysis.className} -> ${verification.target.testFilter}"
            }
        }

        println()
    }

    check(verificationFailures.isEmpty()) {
        "Auto generation completed but verification failed for: ${verificationFailures.joinToString()}"
    }
}

private fun runVerify(task: String?) {
    if (task != null) {
        val result = JvmGradleRunner().run(
            GradleRunRequest(
                module = ":apps:cli",
                task = task,
                workingDirectory = workspaceRoot,
            ),
        )
        val policy = DefaultPolicyEngine().evaluate(defaultPolicyPlan(), result, repairAttempts = 0)

        println("Command: ${result.command.joinToString(" ")}")
        println("Exit: ${result.exitCode}")
        println("Status: ${result.status}")
        println("Policy: ${policy.rationale}")
        println(result.stdout)
        return
    }

    val analyses = resolveChangedAnalyses()
    check(analyses.isNotEmpty()) {
        "No changed ViewModel files were detected in the current diff. Pass an explicit Gradle task or create a diff before running `verify`."
    }

    analyses.forEach { analysis ->
        val plan = createPlan(analysis)
        val target = inferGeneratedTestTarget(analysis, plan)
        requireGeneratedTestFile(target)
        val result = JvmGradleRunner().run(createVerifyRequest(target))
        val policy = DefaultPolicyEngine().evaluate(plan, result, repairAttempts = 0)

        println("Target: ${target.filePath}")
        printAnalysisWarnings(analysis)
        println("Command: ${result.command.joinToString(" ")}")
        println("Exit: ${result.exitCode}")
        println("Status: ${result.status}")
        println("Policy: ${policy.rationale}")
        println(result.stdout)
        println()
    }
}

private fun createPlan(analysis: ViewModelAnalysis): TestPlan {
    val styleGuide = DefaultStyleIndexer().index(workspaceRoot)
    val context = DefaultTestContextBuilder().build("app", analysis, styleGuide)
    val testType = DefaultTestClassifier().classify(analysis, context)
    return DefaultTestPlanner().plan(analysis, context, testType)
}

private fun createBundle(
    analysis: ViewModelAnalysis,
    generator: dev.diff2test.android.testgenerator.TestGenerator = KotlinUnitTestGenerator(),
): GeneratedTestBundle {
    val plan = createPlan(analysis)
    val styleGuide = DefaultStyleIndexer().index(workspaceRoot)
    val context = DefaultTestContextBuilder().build("app", analysis, styleGuide)
    return generator.generate(plan, context, analysis)
}

private fun resolveAnalysis(target: String?): ViewModelAnalysis {
    if (target != null) {
        val requestedPath = resolveTargetPath(target)
        check(Files.exists(requestedPath)) {
            "Target file does not exist: $requestedPath"
        }
        val analyses = SourceBackedViewModelAnalyzer().analyze(explicitTargetChangeSet(target))
        check(analyses.isNotEmpty()) {
            "The source-backed analyzer expects a path ending with ViewModel.kt."
        }
        return analyses.first()
    }

    val detectedAnalyses = resolveChangedAnalyses()
    if (detectedAnalyses.isNotEmpty()) {
        return detectedAnalyses.first()
    }

    error("No changed ViewModel files were detected in the current diff. Pass an explicit ViewModel path.")
}

private fun resolveChangedAnalyses(): List<ViewModelAnalysis> {
    return SourceBackedViewModelAnalyzer().analyze(
        GitDiffChangeDetector().scan(
            request = dev.diff2test.android.changedetector.ScanRequest(
                workingDirectory = workspaceRoot,
            ),
        ),
    )
}

private fun explicitTargetChangeSet(target: String): ChangeSet {
    val path = resolveTargetPath(target)
    check(Files.exists(path)) {
        "Target file does not exist: $path"
    }
    val symbols = extractKotlinSymbols(path)

    return ChangeSet(
        source = ChangeSource.GIT_DIFF,
        files = listOf(
            ChangedFile(
                path = path,
                changedSymbols = symbols,
            ),
        ),
        summary = "Explicit target change set.",
    )
}

private fun resolveTargetPath(target: String): Path {
    val rawPath = Path.of(target)
    return if (rawPath.isAbsolute) rawPath.normalize() else workspaceRoot.resolve(target).normalize()
}

private fun findWorkspaceRoot(start: Path): Path {
    var current: Path? = start.toAbsolutePath().normalize()

    while (current != null) {
        if (Files.exists(current.resolve("gradlew")) || Files.exists(current.resolve("settings.gradle.kts"))) {
            return current
        }
        current = current.parent
    }

    return start.toAbsolutePath().normalize()
}

private fun writeGeneratedFiles(bundle: GeneratedTestBundle, targetPath: Path, outputRootOverride: String?) {
    val outputRoot = outputRootOverride?.let(Path::of) ?: inferModuleRootFromTarget(targetPath)
    val writtenFiles = FileSystemGeneratedTestWriter().write(bundle, outputRoot)

    println("Wrote ${writtenFiles.size} file(s) under $outputRoot")
    writtenFiles.forEach { writtenFile ->
        println("- $writtenFile")
    }

    if (bundle.warnings.isNotEmpty()) {
        println("Warnings:")
        bundle.warnings.forEach(::println)
    }
}

private fun printPreview(bundle: GeneratedTestBundle) {
    bundle.files.forEach { file ->
        println("File: ${file.relativePath}")
        println(file.content)
    }

    if (bundle.warnings.isNotEmpty()) {
        println("Warnings:")
        bundle.warnings.forEach(::println)
    }
}

private fun renderPlan(plan: TestPlan): String {
    return buildString {
        appendLine("Target: ${plan.targetClass}")
        appendLine("Type: ${plan.testType}")
        appendLine("Risk: ${plan.riskLevel}")
        appendLine("Methods: ${plan.targetMethods.joinToString()}")
        appendLine("Scenarios:")
        plan.scenarios.forEach { scenario ->
            appendLine("- ${scenario.name}: ${scenario.expectedOutcome}")
        }
    }
}

private fun defaultPolicyPlan(): TestPlan {
    return TestPlan(
        targetClass = "ManualVerifyTarget",
        targetMethods = emptyList(),
        testType = TestType.LOCAL_UNIT,
        scenarios = emptyList(),
        requiredFakes = emptyList(),
        assertions = emptyList(),
        riskLevel = RiskLevel.LOW,
    )
}

private data class GenerateOptions(
    val target: String?,
    val write: Boolean,
    val outputRoot: String?,
    val aiPreference: AiPreference,
    val model: String?,
    val strictAi: Boolean,
)

private data class InitOptions(
    val force: Boolean,
)

private data class AutoOptions(
    val aiPreference: AiPreference,
    val model: String?,
    val strictAi: Boolean,
    val verifyAfterGenerate: Boolean,
    val repairOnFailure: Boolean,
)

private enum class AiPreference {
    AUTO,
    ENABLED,
    DISABLED,
}

private fun parseGenerateArguments(arguments: List<String>): GenerateOptions {
    var target: String? = null
    var write = false
    var outputRoot: String? = null
    var aiPreference = AiPreference.AUTO
    var model: String? = null
    var strictAi = false
    var index = 0

    while (index < arguments.size) {
        when (val argument = arguments[index]) {
            "--write" -> write = true
            "--ai" -> aiPreference = AiPreference.ENABLED
            "--strict-ai" -> strictAi = true
            "--no-ai" -> aiPreference = AiPreference.DISABLED
            "--model" -> {
                model = arguments.getOrNull(index + 1)
                    ?: error("--model requires a model value")
                index += 1
            }
            "--output-root" -> {
                outputRoot = arguments.getOrNull(index + 1)
                    ?: error("--output-root requires a path value")
                index += 1
            }

            else -> {
                check(target == null) { "Only one target path is supported for generate." }
                target = argument
            }
        }
        index += 1
    }

    return GenerateOptions(
        target = target,
        write = write,
        outputRoot = outputRoot,
        aiPreference = aiPreference,
        model = model,
        strictAi = strictAi,
    )
}

private fun parseInitArguments(arguments: List<String>): InitOptions {
    var force = false
    arguments.forEach { argument ->
        when (argument) {
            "--force" -> force = true
            else -> error("Unknown init option: $argument")
        }
    }
    return InitOptions(force = force)
}

private fun parseAutoArguments(arguments: List<String>): AutoOptions {
    var aiPreference = AiPreference.AUTO
    var model: String? = null
    var strictAi = false
    var verifyAfterGenerate = true
    var repairOnFailure = false
    var index = 0

    while (index < arguments.size) {
        when (arguments[index]) {
            "--ai" -> aiPreference = AiPreference.ENABLED
            "--strict-ai" -> strictAi = true
            "--no-ai" -> aiPreference = AiPreference.DISABLED
            "--no-verify" -> verifyAfterGenerate = false
            "--repair" -> repairOnFailure = true
            "--model" -> {
                model = arguments.getOrNull(index + 1)
                    ?: error("--model requires a model value")
                index += 1
            }

            else -> error("Unknown auto option: ${arguments[index]}")
        }
        index += 1
    }

    return AutoOptions(
        aiPreference = aiPreference,
        model = model,
        strictAi = strictAi,
        verifyAfterGenerate = verifyAfterGenerate,
        repairOnFailure = repairOnFailure,
    )
}

private fun createGenerator(
    aiPreference: AiPreference,
    modelOverride: String?,
    strictAi: Boolean,
): dev.diff2test.android.testgenerator.TestGenerator {
    val loadResult = loadConfig()
    val resolvedFromConfig = resolveAiConfiguration(loadResult, System.getenv(), modelOverride)
    val config = when {
        resolvedFromConfig != null -> toResponsesApiConfig(resolvedFromConfig, System.getenv())
        else -> responsesApiConfigFromEnvironment(modelOverride)
    }
    val configIssue = when {
        resolvedFromConfig?.supportedByGenerator == false -> resolvedFromConfig.issue
        resolvedFromConfig != null && config == null -> resolvedFromConfig.issue ?: "Config could not be resolved."
        else -> null
    }

    return when (aiPreference) {
        AiPreference.ENABLED -> {
            check(config != null && configIssue == null) {
                configIssue
                    ?: "--ai requires a valid config file or one of D2T_AI_AUTH_TOKEN, LLM_API_KEY, ANTHROPIC_AUTH_TOKEN, D2T_OPENAI_API_KEY, or OPENAI_API_KEY."
            }
            println("Using Responses API-compatible test generator (${config.model})")
            ResponsesApiTestGenerator(
                config = config,
                failureMode = AiFailureMode.FAIL_CLOSED,
            )
        }

        AiPreference.DISABLED -> KotlinUnitTestGenerator()
        AiPreference.AUTO -> {
            if (config != null && configIssue == null) {
                println("Using Responses API-compatible test generator (${config.model})")
                ResponsesApiTestGenerator(
                    config = config,
                    failureMode = if (strictAi) {
                        AiFailureMode.FAIL_CLOSED
                    } else {
                        AiFailureMode.FALLBACK_TO_HEURISTIC
                    },
                )
            } else {
                KotlinUnitTestGenerator()
            }
        }
    }
}

private fun printAnalysisWarnings(analysis: ViewModelAnalysis) {
    renderAnalysisWarnings(analysis)?.let(::println)
}

internal fun renderAnalysisWarnings(analysis: ViewModelAnalysis): String? {
    if (analysis.notes.isEmpty()) {
        return null
    }

    return buildString {
        appendLine("Analysis warnings:")
        analysis.notes.distinct().forEach { note ->
            appendLine("- $note")
        }
    }.trimEnd()
}

private fun printHelp() {
    println(renderHelpText())
}

private fun printGeneratedVerification(verification: GeneratedTestVerification) {
    println("Verification target: ${verification.target.filePath}")
    println("Verification command: ${verification.finalResult.command.joinToString(" ")}")
    verification.repairAttempt?.let { repair ->
        println("Repair: ${repair.summary}")
    }
    println("Verification exit: ${verification.finalResult.exitCode}")
    println("Verification status: ${verification.finalResult.status}")
    if (verification.finalResult.stdout.isNotBlank()) {
        println(verification.finalResult.stdout)
    }
}

internal fun renderHelpText(): String {
    return buildString {
        appendLine("d2t commands:")
        appendLine("  init [--force]")
        appendLine("  doctor")
        appendLine("  scan")
        appendLine("  plan [path-to-viewmodel]")
        appendLine("  generate [path-to-viewmodel] [--write] [--output-root path] [--ai|--no-ai] [--strict-ai] [--model model-name]")
        appendLine("  auto [--ai|--no-ai] [--strict-ai] [--model model-name] [--no-verify] [--repair]")
        appendLine("  verify [gradle-task]")
        appendLine("Scope:")
        appendLine("  1.0 target: CLI for diff-driven Android ViewModel local unit test generation and verification")
        appendLine("  MCP app is experimental and currently prints a catalog only")
        appendLine("Verification:")
        appendLine("  auto writes generated tests and verifies them by default")
        appendLine("  --repair enables one bounded repair pass for common import and coroutine test utility failures")
        appendLine("AI:")
        appendLine("  Responses-compatible endpoints only")
        appendLine("Config:")
        appendLine("  ~/.config/d2t/config.toml")
        appendLine("Legacy env fallback:")
        appendLine("  D2T_AI_AUTH_TOKEN / LLM_API_KEY / ANTHROPIC_AUTH_TOKEN / OPENAI_API_KEY")
        appendLine("  D2T_AI_MODEL / STRIX_LLM / ANTHROPIC_MODEL / OPENAI_MODEL")
        appendLine("  D2T_AI_BASE_URL / LLM_API_BASE / ANTHROPIC_BASE_URL / OPENAI_BASE_URL")
        appendLine("  D2T_REASONING_EFFORT / STRIX_RESONING_EFFORT / OPENAI_REASONING_EFFORT")
        appendLine("  D2T_CONNECT_TIMEOUT_SECONDS / LLM_CONNECT_TIMEOUT_SECONDS / OPENAI_CONNECT_TIMEOUT_SECONDS")
        appendLine("  D2T_REQUEST_TIMEOUT_SECONDS / LLM_REQUEST_TIMEOUT_SECONDS / OPENAI_REQUEST_TIMEOUT_SECONDS")
    }.trimEnd()
}
