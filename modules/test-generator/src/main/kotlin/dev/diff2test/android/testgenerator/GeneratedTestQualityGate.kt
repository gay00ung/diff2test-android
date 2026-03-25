package dev.diff2test.android.testgenerator

import dev.diff2test.android.core.GeneratedFile
import dev.diff2test.android.core.GeneratedTestBundle

data class GeneratedTestQualityReport(
    val passed: Boolean,
    val issues: List<String>,
)

class GeneratedTestQualityGate {
    fun evaluate(bundle: GeneratedTestBundle): GeneratedTestQualityReport {
        val issues = buildList {
            if (bundle.files.isEmpty()) {
                add("Generator did not produce any test files.")
            }

            bundle.warnings.forEach { warning ->
                if (
                    "No concrete test heuristics matched" in warning ||
                    "Could not resolve source" in warning ||
                    "Could not infer constructor parameters" in warning
                ) {
                    add("Generator emitted a blocking quality warning: $warning")
                }
            }

            bundle.files.forEach { file ->
                addAll(checkFile(file))
            }
        }.distinct()

        return GeneratedTestQualityReport(
            passed = issues.isEmpty(),
            issues = issues,
        )
    }

    private fun checkFile(file: GeneratedFile): List<String> {
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

private val TRIVIAL_ASSERT_PATTERN = Regex("""assertTrue\(\s*true\b""")
private val ASSERTION_PATTERN = Regex(
    """\b(assertEquals|assertTrue|assertFalse|assertNull|assertNotNull|assertFails|assertContentEquals|assertContains|assertIs)\s*\(""",
)
private val TEST_BLOCK_PATTERN = Regex("""@Test[\s\S]*?(?=\n\s*@Test|\n})""")
