package dev.diff2test.android.cli

import dev.diff2test.android.core.GradleRunRequest
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.core.ViewModelAnalysis
import dev.diff2test.android.testgenerator.inferModuleRootFromTarget
import java.nio.file.Files
import java.nio.file.Path

internal data class GeneratedTestTarget(
    val moduleRoot: Path,
    val filePath: Path,
    val testFilter: String,
    val gradleTask: String,
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

    if (relativePath.nameCount == 0) {
        return "test"
    }

    val projectPath = (0 until relativePath.nameCount)
        .joinToString(":") { index -> relativePath.getName(index).toString() }
    return ":$projectPath:test"
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
