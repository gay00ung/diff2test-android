package dev.diff2test.android.testgenerator

import dev.diff2test.android.core.GeneratedFile
import dev.diff2test.android.core.GeneratedTestBundle
import dev.diff2test.android.core.StyleGuide

data class GeneratedTestQualityReport(
    val passed: Boolean,
    val issues: List<String>,
)

class GeneratedTestQualityGate {
    fun evaluate(bundle: GeneratedTestBundle, styleGuide: StyleGuide = StyleGuide()): GeneratedTestQualityReport {
        val issues = buildList {
            if (bundle.files.isEmpty()) {
                add("Generator did not produce any test files.")
            }

            bundle.warnings.forEach { warning ->
                if (
                    "No concrete test heuristics matched" in warning ||
                    "Could not resolve source" in warning ||
                    "Could not infer constructor parameters" in warning ||
                    warning.startsWith("TODO:") ||
                    "mockkStatic" in warning ||
                    "cancel()" in warning
                ) {
                    add("Generator emitted a blocking quality warning: $warning")
                }
            }

            bundle.files.forEach { file ->
                addAll(checkFile(file, styleGuide))
            }
        }.distinct()

        return GeneratedTestQualityReport(
            passed = issues.isEmpty(),
            issues = issues,
        )
    }

    private fun checkFile(file: GeneratedFile, styleGuide: StyleGuide): List<String> {
        val content = file.content
        val issues = mutableListOf<String>()

        if ("Replace this placeholder with project-specific setup and assertions." in content) {
            issues += "${file.relativePath}: placeholder test body is not allowed."
        }

        if (TRIVIAL_ASSERT_PATTERN.containsMatchIn(content)) {
            issues += "${file.relativePath}: trivial always-true assertion is not allowed."
        }

        if ("TODO(" in content) {
            issues += "${file.relativePath}: unresolved TODO() remains in generated output."
        }

        if (TODO_COMMENT_PATTERN.containsMatchIn(content)) {
            issues += "${file.relativePath}: TODO-style comments or caveat placeholders are not allowed."
        }

        if (MOCKING_PATTERN.containsMatchIn(content)) {
            issues += "${file.relativePath}: mocking-framework-based output is not allowed in generated tests."
        }

        if (STATIC_MOCKING_PATTERN.containsMatchIn(content)) {
            issues += "${file.relativePath}: static mocking is not allowed in generated tests."
        }

        if (CANCEL_PATTERN.containsMatchIn(content)) {
            issues += "${file.relativePath}: using cancel() to finish coroutine work is not allowed."
        }

        if (
            styleGuide.coroutineEntryPoint != "runTest" &&
            (RUN_TEST_API_PATTERN.containsMatchIn(content) || TEST_DISPATCHER_API_PATTERN.containsMatchIn(content))
        ) {
            issues += "${file.relativePath}: module does not declare kotlinx-coroutines-test, but generated output still uses coroutine-test APIs."
        }

        duplicateTargetClassName(file, content)?.let { issue ->
            issues += issue
        }

        detachedDispatcherNames(content).forEach { dispatcherName ->
            issues += "${file.relativePath}: StandardTestDispatcher() must be bound to runTest via testScheduler or used as runTest($dispatcherName)."
        }

        val testBlocks = extractTestBlocks(content)
        if (testBlocks.isEmpty()) {
            issues += "${file.relativePath}: generated file does not contain any @Test methods."
        } else {
            testBlocks.forEachIndexed { index, block ->
                if (!ASSERTION_PATTERN.containsMatchIn(block)) {
                    issues += "${file.relativePath}: test #${index + 1} does not contain a meaningful assertion."
                }
            }
        }

        return issues
    }

    private fun extractTestBlocks(content: String): List<String> {
        return TEST_BLOCK_PATTERN.findAll(content).map { it.value }.toList()
    }
}

private fun duplicateTargetClassName(file: GeneratedFile, content: String): String? {
    val fileName = file.relativePath.fileName.toString()
    if (!fileName.endsWith("GeneratedTest.kt")) return null
    val targetClass = fileName.removeSuffix("GeneratedTest.kt")
    if (targetClass.isBlank()) return null
    val duplicatePattern = Regex("""(?m)^\s*(private\s+)?class\s+${Regex.escape(targetClass)}\b""")
    return if (duplicatePattern.containsMatchIn(content)) {
        "${file.relativePath}: redefining or subclassing the target ViewModel inside the generated test file is not allowed."
    } else {
        null
    }
}

private val TRIVIAL_ASSERT_PATTERN = Regex("""assertTrue\(\s*true\b""")
private val TODO_COMMENT_PATTERN = Regex("""(?m)^\s*//\s*TODO:|(?m)^\s*/\*\s*TODO:""")
private val MOCKING_PATTERN = Regex("""\b(io\.mockk|MockK\b|mockk\(|every\s*\{|coEvery\s*\{|Mockito\b)""")
private val STATIC_MOCKING_PATTERN = Regex("""\bmockkStatic\s*\(""")
private val CANCEL_PATTERN = Regex("""\.\s*cancel\s*\(""")
private val RUN_TEST_API_PATTERN = Regex("""\brunTest\s*\(""")
private val TEST_DISPATCHER_API_PATTERN = Regex("""\b(StandardTestDispatcher|advanceUntilIdle|testScheduler)\b""")
private val CLASS_LEVEL_TEST_DISPATCHER_PATTERN = Regex(
    """(?m)^\s*(private\s+)?val\s+\w+\s*=\s*StandardTestDispatcher\(\)\s*$""",
)
private val ASSERTION_PATTERN = Regex(
    """\b(assertEquals|assertTrue|assertFalse|assertNull|assertNotNull|assertFails|assertContentEquals|assertContains|assertIs)\s*\(""",
)
private val TEST_BLOCK_PATTERN = Regex("""@Test[\s\S]*?(?=\n\s*@Test|\n})""")

private fun detachedDispatcherNames(content: String): List<String> {
    return CLASS_LEVEL_TEST_DISPATCHER_PATTERN.findAll(content)
        .mapNotNull { match ->
            val line = match.value
            val dispatcherName = line.substringAfter("val ").substringBefore('=').trim()
            if (dispatcherName.isBlank()) {
                null
            } else if (isDispatcherBoundToRunTest(content, dispatcherName)) {
                null
            } else {
                dispatcherName
            }
        }
        .toList()
}

private fun isDispatcherBoundToRunTest(content: String, dispatcherName: String): Boolean {
    val escaped = Regex.escape(dispatcherName)
    val directRunTestPattern = Regex("""runTest\s*\(\s*$escaped\s*\)""")
    val namedRunTestPattern = Regex("""runTest\s*\(\s*context\s*=\s*$escaped\s*\)""")
    return directRunTestPattern.containsMatchIn(content) || namedRunTestPattern.containsMatchIn(content)
}
