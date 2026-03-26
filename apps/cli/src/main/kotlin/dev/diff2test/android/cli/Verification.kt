package dev.diff2test.android.cli

import dev.diff2test.android.core.GradleRunRequest
import dev.diff2test.android.core.GeneratedTestBundle
import dev.diff2test.android.core.RepairAttempt
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.core.ViewModelAnalysis
import dev.diff2test.android.gradlerunner.GradleRunner
import dev.diff2test.android.gradlerunner.JvmGradleRunner
import dev.diff2test.android.testgenerator.FileSystemGeneratedTestWriter
import dev.diff2test.android.testgenerator.inferModuleRootFromTarget
import dev.diff2test.android.testrepair.BoundedRepairer
import dev.diff2test.android.testrepair.TestRepairer
import java.nio.file.Files
import java.nio.file.Path

internal data class GeneratedTestTarget(
    val moduleRoot: Path,
    val filePath: Path,
    val testFilter: String,
    val gradleTask: String,
)

internal data class GeneratedTestVerification(
    val target: GeneratedTestTarget,
    val initialResult: dev.diff2test.android.core.ExecutionResult,
    val finalResult: dev.diff2test.android.core.ExecutionResult,
    val repairAttempt: RepairAttempt? = null,
)

internal fun inferGeneratedTestTarget(
    analysis: ViewModelAnalysis,
    plan: TestPlan,
): GeneratedTestTarget {
    val moduleRoot = inferModuleRootFromTarget(analysis.filePath).toAbsolutePath().normalize()
    val packageName = analysis.packageName.ifBlank { "dev.diff2test.android.generated" }
    val relativePath = Path.of(
        "src/test/kotlin/" + packageName.replace('.', '/') + "/${plan.targetClass}GeneratedTest.kt",
    )
    val filePath = moduleRoot.resolve(relativePath).normalize()
    val testClassName = "${plan.targetClass}GeneratedTest"
    val testFilter = if (analysis.packageName.isBlank()) {
        testClassName
    } else {
        "${analysis.packageName}.$testClassName"
    }

    return GeneratedTestTarget(
        moduleRoot = moduleRoot,
        filePath = filePath,
        testFilter = testFilter,
        gradleTask = inferModuleTestTask(moduleRoot),
    )
}

internal fun inferModuleTestTask(moduleRoot: Path): String {
    val normalizedModuleRoot = moduleRoot.toAbsolutePath().normalize()
    val buildRoot = findBuildRoot(normalizedModuleRoot)
    val relativePath = buildRoot.relativize(normalizedModuleRoot)
    val taskName = if (isAndroidModule(normalizedModuleRoot)) {
        "testDebugUnitTest"
    } else {
        "test"
    }

    if (relativePath.nameCount == 0) {
        return taskName
    }

    val projectPath = (0 until relativePath.nameCount)
        .joinToString(":") { index -> relativePath.getName(index).toString() }
    return ":$projectPath:$taskName"
}

internal fun createVerifyRequest(target: GeneratedTestTarget): GradleRunRequest {
    return GradleRunRequest(
        module = target.gradleTask.removeSuffix(":test"),
        task = target.gradleTask,
        testFilter = target.testFilter,
        workingDirectory = target.moduleRoot,
    )
}

internal fun requireGeneratedTestFile(target: GeneratedTestTarget) {
    check(Files.exists(target.filePath)) {
        "Generated test file not found: ${target.filePath}. Run `generate --write` or `auto` first."
    }
}

internal fun verifyGeneratedBundle(
    analysis: ViewModelAnalysis,
    bundle: GeneratedTestBundle,
    gradleRunner: GradleRunner = JvmGradleRunner(),
    repairer: TestRepairer = BoundedRepairer(),
    attemptRepair: Boolean = false,
): GeneratedTestVerification {
    val target = inferGeneratedTestTarget(analysis, bundle.plan)
    requireGeneratedTestFile(target)

    val initialResult = gradleRunner.run(createVerifyRequest(target))
    if (initialResult.status != dev.diff2test.android.core.ExecutionStatus.FAILED || !attemptRepair) {
        return GeneratedTestVerification(
            target = target,
            initialResult = initialResult,
            finalResult = initialResult,
        )
    }

    val repairAttempt = repairer.repair(
        plan = bundle.plan,
        bundle = bundle,
        failure = initialResult,
        attemptNumber = 1,
    )

    if (!repairAttempt.applied) {
        return GeneratedTestVerification(
            target = target,
            initialResult = initialResult,
            finalResult = initialResult,
            repairAttempt = repairAttempt,
        )
    }

    FileSystemGeneratedTestWriter().write(
        bundle.copy(files = repairAttempt.updatedFiles),
        target.moduleRoot,
    )

    val finalResult = gradleRunner.run(createVerifyRequest(target))
    return GeneratedTestVerification(
        target = target,
        initialResult = initialResult,
        finalResult = finalResult,
        repairAttempt = repairAttempt,
    )
}

private fun findBuildRoot(start: Path): Path {
    var current: Path? = start.toAbsolutePath().normalize()

    while (current != null) {
        if (Files.exists(current.resolve("gradlew")) || Files.exists(current.resolve("settings.gradle.kts"))) {
            return current
        }
        current = current.parent
    }

    return start.toAbsolutePath().normalize()
}

internal fun isAndroidModule(moduleRoot: Path): Boolean {
    val buildFiles = listOf(
        moduleRoot.resolve("build.gradle.kts"),
        moduleRoot.resolve("build.gradle"),
    ).filter(Files::exists)

    if (buildFiles.isEmpty()) {
        return false
    }

    val buildScript = buildFiles.joinToString("\n") { Files.readString(it) }
    return "com.android.application" in buildScript ||
        "com.android.library" in buildScript ||
        "android.application" in buildScript ||
        "android.library" in buildScript ||
        "namespace =" in buildScript && "android {" in buildScript
}
