package dev.diff2test.android.testgenerator

import dev.diff2test.android.core.CollaboratorDependency
import dev.diff2test.android.core.RiskLevel
import dev.diff2test.android.core.StyleGuide
import dev.diff2test.android.core.TestContext
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.core.TestType
import dev.diff2test.android.core.ViewModelAnalysis
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

class OpenAiResponsesTestGeneratorTest {
    @Test
    fun `reads strix and llm environment variables without remapping`() {
        val config = responsesApiConfigFromEnvironment(
            environment = mapOf(
                "LLM_API_KEY" to "sk-local",
                "LLM_API_BASE" to "http://127.0.0.1:12345",
                "STRIX_LLM" to "glm-4.7-flash-claude-opus-4.5-high-reasoning-distill",
                "STRIX_RESONING_EFFORT" to "high",
                "OPENAI_API_KEY" to "sk-ignored",
                "OPENAI_MODEL" to "gpt-ignored",
            ),
        )

        assertEquals("sk-local", config?.apiKey)
        assertEquals("http://127.0.0.1:12345", config?.baseUrl)
        assertEquals("glm-4.7-flash-claude-opus-4.5-high-reasoning-distill", config?.model)
        assertEquals("high", config?.reasoningEffort)
    }

    @Test
    fun `includes reasoning effort in responses request when configured`() {
        val requestBody = buildResponsesRequest(
            config = ResponsesApiConfig(
                apiKey = "sk-local",
                model = "qwen3-coder-next-mlx",
                baseUrl = "http://127.0.0.1:12345",
                reasoningEffort = "high",
            ),
            instructions = "Generate tests",
            input = "input",
        )

        assertTrue("\"reasoning\"" in requestBody)
        assertTrue("\"effort\":\"high\"" in requestBody)
    }

    @Test
    fun `prompt includes collaborator source when available`() {
        val fixturePath = findRepoRoot()
            .resolve("fixtures/sample-app/app/src/main/java/com/example/auth/SignUpViewModel.kt")
        val analysis = ViewModelAnalysis(
            className = "SignUpViewModel",
            packageName = "com.example.auth",
            filePath = fixturePath,
            constructorDependencies = listOf(
                CollaboratorDependency(
                    name = "registerUser",
                    type = "RegisterUserUseCase",
                    role = "use case",
                ),
            ),
        )
        val plan = TestPlan(
            targetClass = "SignUpViewModel",
            targetMethods = listOf("submitRegistration"),
            testType = TestType.LOCAL_UNIT,
            scenarios = emptyList(),
            requiredFakes = emptyList(),
            assertions = emptyList(),
            riskLevel = RiskLevel.LOW,
        )
        val context = TestContext(
            moduleName = "app",
            styleGuide = StyleGuide(),
        )

        val prompt = buildPromptSpec(plan, context, analysis)

        assertContains(prompt.input, "## Dependency Sources")
        assertContains(prompt.input, "### RegisterUserUseCase")
        assertContains(prompt.input, "suspend operator fun invoke")
        assertContains(prompt.input, "Result<String>")
        assertContains(prompt.instructions, "preserve its method names, parameter lists, and exact return types")
    }

    @Test
    fun `extracts structured payload from responses api body`() {
        val responseBody = """
            {
              "output": [
                {
                  "type": "message",
                  "content": [
                    {
                      "type": "output_text",
                      "text": "{\"content\":\"```kotlin\\npackage com.example.auth\\n\\nclass SignUpViewModelGeneratedTest\\n```\",\"warnings\":[\"generated from ai\"]}"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val payload = extractStructuredPayload(responseBody)

        assertEquals(
            "package com.example.auth\n\nclass SignUpViewModelGeneratedTest",
            payload.content,
        )
        assertEquals(listOf("generated from ai"), payload.warnings)
    }

    private fun findRepoRoot(): Path {
        var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

        while (current != null) {
            if (Files.exists(current.resolve("fixtures/sample-app"))) {
                return current
            }
            current = current.parent
        }

        error("Could not locate repository root from ${System.getProperty("user.dir")}")
    }
}
