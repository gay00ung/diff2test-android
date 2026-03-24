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
import dev.diff2test.android.core.SymbolKind
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.core.ViewModelAnalysis
import dev.diff2test.android.gradlerunner.JvmGradleRunner
import dev.diff2test.android.kotlinanalyzer.StubViewModelAnalyzer
import dev.diff2test.android.policy.DefaultPolicyEngine
import dev.diff2test.android.styleindex.DefaultStyleIndexer
import dev.diff2test.android.testclassifier.DefaultTestClassifier
import dev.diff2test.android.testgenerator.FileSystemGeneratedTestWriter
import dev.diff2test.android.testgenerator.KotlinUnitTestGenerator
import dev.diff2test.android.testgenerator.inferModuleRootFromTarget
import dev.diff2test.android.testplanner.DefaultTestPlanner
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    when (val command = args.firstOrNull()) {
        "scan" -> runScan()
        "plan" -> runPlan(args.getOrNull(1))
        "generate" -> runGenerate(parseGenerateArguments(args.drop(1)))
        "auto" -> runAuto()
        "verify" -> runVerify(args.getOrNull(1))
        null, "help", "--help", "-h" -> printHelp()
        else -> {
            println("Unknown command: $command")
            printHelp()
        }
    }
}

private val workspaceRoot: Path = findWorkspaceRoot(Path.of(System.getProperty("user.dir")))

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
}

private fun runGenerate(options: GenerateOptions) {
    val analysis = resolveAnalysis(options.target)
    val bundle = createBundle(analysis)

    if (options.write) {
        writeGeneratedFiles(bundle, analysis.filePath, options.outputRoot)
    } else {
        printPreview(bundle)
    }
}

private fun runAuto() {
    val analyses = resolveChangedAnalyses()
    check(analyses.isNotEmpty()) {
        "No changed ViewModel files were detected in the current diff."
    }

    analyses.forEach { analysis ->
        val bundle = createBundle(analysis)
        println("Generating tests for ${analysis.filePath}")
        writeGeneratedFiles(bundle, analysis.filePath, outputRootOverride = null)
        println()
    }
}

private fun runVerify(task: String?) {
    val requestedTask = task ?: ":apps:cli:test"
    val result = JvmGradleRunner().run(
        GradleRunRequest(
            module = ":apps:cli",
            task = requestedTask,
            workingDirectory = workspaceRoot,
        ),
    )
    val policy = DefaultPolicyEngine().evaluate(createPlan(resolveAnalysis(null)), result, repairAttempts = 0)

    println("Command: ${result.command.joinToString(" ")}")
    println("Exit: ${result.exitCode}")
    println("Status: ${result.status}")
    println("Policy: ${policy.rationale}")
    println(result.stdout)
}

private fun createPlan(analysis: ViewModelAnalysis): TestPlan {
    val styleGuide = DefaultStyleIndexer().index(workspaceRoot)
    val context = DefaultTestContextBuilder().build("app", analysis, styleGuide)
    val testType = DefaultTestClassifier().classify(analysis, context)
    return DefaultTestPlanner().plan(analysis, context, testType)
}

private fun createBundle(analysis: ViewModelAnalysis): GeneratedTestBundle {
    val plan = createPlan(analysis)
    val styleGuide = DefaultStyleIndexer().index(workspaceRoot)
    val context = DefaultTestContextBuilder().build("app", analysis, styleGuide)
    return KotlinUnitTestGenerator().generate(plan, context, analysis)
}

private fun resolveAnalysis(target: String?): ViewModelAnalysis {
    if (target != null) {
        val analyses = StubViewModelAnalyzer().analyze(explicitTargetChangeSet(target))
        check(analyses.isNotEmpty()) {
            "The current stub analyzer expects a path ending with ViewModel.kt."
        }
        return analyses.first()
    }

    val detectedAnalyses = resolveChangedAnalyses()
    if (detectedAnalyses.isNotEmpty()) {
        return detectedAnalyses.first()
    }

    return StubViewModelAnalyzer().analyze(explicitTargetChangeSet("fixtures/sample-app/app/src/main/java/com/example/auth/SignUpViewModel.kt")).first()
}

private fun resolveChangedAnalyses(): List<ViewModelAnalysis> {
    return StubViewModelAnalyzer().analyze(
        GitDiffChangeDetector().scan(
            request = dev.diff2test.android.changedetector.ScanRequest(
                workingDirectory = workspaceRoot,
            ),
        ),
    )
}

private fun explicitTargetChangeSet(target: String): ChangeSet {
    val rawPath = Path.of(target)
    val path = if (rawPath.isAbsolute) rawPath.normalize() else workspaceRoot.resolve(target).normalize()
    val symbols = if (Files.exists(path)) {
        extractKotlinSymbols(path)
    } else {
        listOf(
            ChangedSymbol(
                name = "loadData",
                kind = SymbolKind.METHOD,
                signature = "suspend fun loadData()",
            ),
        )
    }

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

private data class GenerateOptions(
    val target: String?,
    val write: Boolean,
    val outputRoot: String?,
)

private fun parseGenerateArguments(arguments: List<String>): GenerateOptions {
    var target: String? = null
    var write = false
    var outputRoot: String? = null
    var index = 0

    while (index < arguments.size) {
        when (val argument = arguments[index]) {
            "--write" -> write = true
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
    )
}

private fun printHelp() {
    println("d2t commands:")
    println("  scan")
    println("  plan [path-to-viewmodel]")
    println("  generate [path-to-viewmodel] [--write] [--output-root path]")
    println("  auto")
    println("  verify [gradle-task]")
}
