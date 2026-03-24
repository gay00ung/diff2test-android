package dev.diff2test.android.testgenerator

import dev.diff2test.android.core.GeneratedFile
import dev.diff2test.android.core.GeneratedTestBundle
import dev.diff2test.android.core.TestContext
import dev.diff2test.android.core.TestPlan
import java.nio.file.Path

interface TestGenerator {
    fun generate(plan: TestPlan, context: TestContext): GeneratedTestBundle
}

class KotlinUnitTestGenerator : TestGenerator {
    override fun generate(plan: TestPlan, context: TestContext): GeneratedTestBundle {
        val packageName = "dev.diff2test.android.generated"
        val relativePath = Path.of(
            "src/test/kotlin/" + packageName.replace('.', '/') + "/${plan.targetClass}Test.kt",
        )

        val content = buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import kotlin.test.Test")
            appendLine("import kotlin.test.assertTrue")
            appendLine()
            appendLine("class ${plan.targetClass}Test {")
            appendLine()

            plan.scenarios.forEach { scenario ->
                val testName = scenario.name.replace('`', '\'')
                appendLine("    @Test")
                appendLine("    fun `$testName`() {")
                appendLine("        // Replace this placeholder with project-specific setup and assertions.")
                appendLine("        assertTrue(true, \"${scenario.expectedOutcome}\")")
                appendLine("    }")
                appendLine()
            }

            appendLine("}")
        }

        return GeneratedTestBundle(
            plan = plan,
            files = listOf(GeneratedFile(relativePath = relativePath, content = content)),
            warnings = listOf(
                "This generator currently emits a compile-oriented scaffold, not repository-specific fakes.",
            ),
        )
    }
}

