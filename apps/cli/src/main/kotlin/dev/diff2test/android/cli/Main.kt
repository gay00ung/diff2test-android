package dev.diff2test.android.cli

import dev.diff2test.android.changedetector.GitDiffChangeDetector
import dev.diff2test.android.contextbuilder.DefaultTestContextBuilder
import dev.diff2test.android.core.ChangeSet
import dev.diff2test.android.core.ChangeSource
import dev.diff2test.android.core.ChangedFile
import dev.diff2test.android.core.ChangedSymbol
import dev.diff2test.android.core.GradleRunRequest
import dev.diff2test.android.core.SymbolKind
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.gradlerunner.JvmGradleRunner
import dev.diff2test.android.kotlinanalyzer.StubViewModelAnalyzer
import dev.diff2test.android.policy.DefaultPolicyEngine
import dev.diff2test.android.styleindex.DefaultStyleIndexer
import dev.diff2test.android.testclassifier.DefaultTestClassifier
import dev.diff2test.android.testgenerator.KotlinUnitTestGenerator
import dev.diff2test.android.testplanner.DefaultTestPlanner
import java.nio.file.Path

fun main(args: Array<String>) {
    when (val command = args.firstOrNull()) {
        "scan" -> runScan()
        "plan" -> runPlan(args.getOrNull(1))
        "generate" -> runGenerate(args.getOrNull(1))
        "verify" -> runVerify(args.getOrNull(1))
        null, "help", "--help", "-h" -> printHelp()
        else -> {
            println("Unknown command: $command")
            printHelp()
        }
    }
}

private fun runScan() {
    val changeSet = GitDiffChangeDetector().scan()
    println("Source: ${changeSet.source}")
    println("Base: ${changeSet.baseRef}")
    println("Head: ${changeSet.headRef}")
    println("Summary: ${changeSet.summary}")
}

private fun runPlan(target: String?) {
    val plan = createPlan(target)
    println(renderPlan(plan))
}

private fun runGenerate(target: String?) {
    val plan = createPlan(target)
    val analysis = buildAnalysisInput(target)
    val styleGuide = DefaultStyleIndexer().index(Path.of("."))
    val context = DefaultTestContextBuilder().build("app", analysis.first(), styleGuide)
    val bundle = KotlinUnitTestGenerator().generate(plan, context)

    bundle.files.forEach { file ->
        println("File: ${file.relativePath}")
        println(file.content)
    }

    if (bundle.warnings.isNotEmpty()) {
        println("Warnings:")
        bundle.warnings.forEach(::println)
    }
}

private fun runVerify(task: String?) {
    val requestedTask = task ?: ":apps:cli:test"
    val result = JvmGradleRunner().run(
        GradleRunRequest(
            module = ":apps:cli",
            task = requestedTask,
            workingDirectory = Path.of(System.getProperty("user.dir")),
        ),
    )
    val policy = DefaultPolicyEngine().evaluate(createPlan(null), result, repairAttempts = 0)

    println("Command: ${result.command.joinToString(" ")}")
    println("Exit: ${result.exitCode}")
    println("Status: ${result.status}")
    println("Policy: ${policy.rationale}")
    println(result.stdout)
}

private fun createPlan(target: String?): TestPlan {
    val analysis = buildAnalysisInput(target).first()
    val styleGuide = DefaultStyleIndexer().index(Path.of("."))
    val context = DefaultTestContextBuilder().build("app", analysis, styleGuide)
    val testType = DefaultTestClassifier().classify(analysis, context)
    return DefaultTestPlanner().plan(analysis, context, testType)
}

private fun buildAnalysisInput(target: String?): List<dev.diff2test.android.core.ViewModelAnalysis> {
    val path = Path.of(target ?: "app/src/main/java/com/example/LoginViewModel.kt")
    val changeSet = ChangeSet(
        source = ChangeSource.GIT_DIFF,
        files = listOf(
            ChangedFile(
                path = path,
                changedSymbols = listOf(
                    ChangedSymbol(
                        name = "loadData",
                        kind = SymbolKind.METHOD,
                        signature = "suspend fun loadData()",
                    ),
                ),
            ),
        ),
        summary = "Synthetic change set for CLI bootstrap.",
    )
    return StubViewModelAnalyzer().analyze(changeSet)
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

private fun printHelp() {
    println("d2t commands:")
    println("  scan")
    println("  plan [path-to-viewmodel]")
    println("  generate [path-to-viewmodel]")
    println("  verify [gradle-task]")
}

