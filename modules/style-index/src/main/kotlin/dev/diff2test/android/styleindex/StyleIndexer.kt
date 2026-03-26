package dev.diff2test.android.styleindex

import dev.diff2test.android.core.StyleGuide
import java.nio.file.Files
import java.nio.file.Path

interface StyleIndexer {
    fun index(moduleRoot: Path): StyleGuide
}

class DefaultStyleIndexer : StyleIndexer {
    override fun index(moduleRoot: Path): StyleGuide {
        val hasTestDirectory = Files.exists(moduleRoot.resolve("src/test"))
        if (!hasTestDirectory) {
            return StyleGuide()
        }

        val buildRoot = findBuildRoot(moduleRoot)
        val existingTests = loadTestSources(moduleRoot)
        val combinedText = buildString {
            existingTests.forEach {
                appendLine(it)
                appendLine()
            }
            loadBuildFiles(moduleRoot, buildRoot).forEach {
                appendLine(it)
                appendLine()
            }
        }

        val assertionStyle = when {
            "import org.junit.Test" in combinedText || "org.junit.Assert" in combinedText || "testImplementation(libs.junit)" in combinedText || "junit:junit" in combinedText -> "junit4"
            "import kotlin.test.Test" in combinedText || "kotlin-test" in combinedText -> "kotlin.test"
            else -> "kotlin.test"
        }

        val coroutineEntryPoint = if (
            "import kotlinx.coroutines.test.runTest" in combinedText ||
            "kotlinx-coroutines-test" in combinedText ||
            "coroutines-test" in combinedText
        ) {
            "runTest"
        } else {
            "unavailable"
        }

        return StyleGuide(
            mockLibrary = "project-default",
            assertionStyle = assertionStyle,
            coroutineEntryPoint = coroutineEntryPoint,
            flowProbe = "project-default",
            namingPattern = "project-default",
        )
    }
}

private fun loadTestSources(moduleRoot: Path): List<String> {
    val testRoot = moduleRoot.resolve("src/test")
    if (!Files.exists(testRoot)) return emptyList()
    return Files.walk(testRoot).use { paths ->
        paths
            .filter(Files::isRegularFile)
            .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }
            .filter { !it.fileName.toString().endsWith("GeneratedTest.kt") }
            .limit(20)
            .map { Files.readString(it) }
            .toList()
    }
}

private fun loadBuildFiles(moduleRoot: Path, buildRoot: Path): List<String> {
    val candidates = buildList {
        add(moduleRoot.resolve("build.gradle.kts"))
        add(moduleRoot.resolve("build.gradle"))
        add(buildRoot.resolve("gradle/libs.versions.toml"))
    }
    return candidates.filter(Files::exists).map(Files::readString)
}

private fun findBuildRoot(start: Path): Path {
    var current: Path? = start.toAbsolutePath().normalize()
    while (current != null) {
        if (Files.exists(current.resolve("settings.gradle.kts")) || Files.exists(current.resolve("gradlew"))) {
            return current
        }
        current = current.parent
    }
    return start.toAbsolutePath().normalize()
}
