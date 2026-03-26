package dev.diff2test.android.testgenerator

import dev.diff2test.android.core.CollaboratorDependency
import dev.diff2test.android.core.RiskLevel
import dev.diff2test.android.core.StyleGuide
import dev.diff2test.android.core.TestContext
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.core.TestScenario
import dev.diff2test.android.core.TestType
import dev.diff2test.android.core.ViewModelAnalysis
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
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
        assertEquals(30L, config?.connectTimeoutSeconds)
        assertEquals(180L, config?.requestTimeoutSeconds)
    }

    @Test
    fun `includes reasoning effort in responses request when configured`() {
        val requestBody = buildResponsesRequest(
            config = ResponsesApiConfig(
                apiKey = "sk-local",
                model = "qwen3-coder-next-mlx",
                baseUrl = "http://127.0.0.1:12345",
                reasoningEffort = "high",
                requestTimeoutSeconds = 240,
            ),
            instructions = "Generate tests",
            input = "input",
        )

        assertTrue("\"reasoning\"" in requestBody)
        assertTrue("\"effort\":\"high\"" in requestBody)
    }

    @Test
    fun `builds anthropic messages request with system and user content`() {
        val requestBody = buildAnthropicMessagesRequest(
            config = AnthropicMessagesConfig(
                apiKey = "sk-ant",
                model = "claude-sonnet-4-5",
                baseUrl = "https://api.anthropic.com/v1",
            ),
            instructions = "Generate tests",
            input = "input",
        )

        assertContains(requestBody, "\"model\":\"claude-sonnet-4-5\"")
        assertContains(requestBody, "\"system\":\"Generate tests\\nReturn valid JSON only.\"")
        assertContains(requestBody, "\"messages\"")
        assertContains(requestBody, "\"role\":\"user\"")
    }

    @Test
    fun `builds chat completions request with system and user messages`() {
        val requestBody = buildChatCompletionsRequest(
            config = ChatCompletionsConfig(
                apiKey = "sk-local",
                model = "qwen3-coder-next-mlx",
                baseUrl = "http://127.0.0.1:12345/v1",
            ),
            instructions = "Generate tests",
            input = "input",
        )

        assertContains(requestBody, "\"model\":\"qwen3-coder-next-mlx\"")
        assertContains(requestBody, "\"temperature\":0")
        assertContains(requestBody, "\"response_format\":{\"type\":\"json_schema\"")
        assertContains(requestBody, "\"name\":\"generated_viewmodel_test\"")
        assertContains(requestBody, "\"messages\"")
        assertContains(requestBody, "\"role\":\"system\"")
        assertContains(requestBody, "\"role\":\"user\"")
    }

    @Test
    fun `extracts structured payload from anthropic response body`() {
        val responseBody = """
            {
              "content": [
                {
                  "type": "text",
                  "text": "{\"content\":\"package com.example.auth\\n\\nclass SignUpViewModelGeneratedTest\",\"warnings\":[\"anthropic\"]}"
                }
              ]
            }
        """.trimIndent()

        val payload = extractAnthropicStructuredPayload(responseBody)

        assertEquals(
            "package com.example.auth\n\nclass SignUpViewModelGeneratedTest",
            payload.content,
        )
        assertEquals(listOf("anthropic"), payload.warnings)
    }

    @Test
    fun `extracts structured payload from chat completions response body`() {
        val responseBody = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "{\"content\":\"package com.example.auth\\n\\nclass SignUpViewModelGeneratedTest\",\"warnings\":[\"chat\"]}"
                  }
                }
              ]
            }
        """.trimIndent()

        val payload = extractChatCompletionsStructuredPayload(responseBody)

        assertEquals(
            "package com.example.auth\n\nclass SignUpViewModelGeneratedTest",
            payload.content,
        )
        assertEquals(listOf("chat"), payload.warnings)
    }

    @Test
    fun `builds gemini generate content request with response schema`() {
        val requestBody = buildGeminiGenerateContentRequest(
            config = GeminiGenerateContentConfig(
                apiKey = "sk-gem",
                model = "gemini-2.5-pro",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            ),
            instructions = "Generate tests",
            input = "input",
        )

        assertContains(requestBody, "\"system_instruction\"")
        assertContains(requestBody, "\"responseMimeType\":\"application/json\"")
        assertContains(requestBody, "\"responseSchema\"")
        assertContains(requestBody, "\"contents\"")
    }

    @Test
    fun `extracts structured payload from gemini response body`() {
        val responseBody = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"content\":\"package com.example.auth\\n\\nclass SignUpViewModelGeneratedTest\",\"warnings\":[\"gemini\"]}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val payload = extractGeminiStructuredPayload(responseBody)

        assertEquals(
            "package com.example.auth\n\nclass SignUpViewModelGeneratedTest",
            payload.content,
        )
        assertEquals(listOf("gemini"), payload.warnings)
    }

    @Test
    fun `normalizes junit test imports to kotlin test`() {
        val content = """
            package com.example.auth

            import org.junit.Test

            class SignUpViewModelGeneratedTest
        """.trimIndent()

        val sanitized = sanitizeGeneratedKotlin(content)

        assertContains(sanitized, "import kotlin.test.Test")
        assertTrue("import org.junit.Test" !in sanitized)
    }

    @Test
    fun `stabilizes generated coroutine tests around test scheduler and idle advancement`() {
        val content = """
            package com.example.auth

            import kotlinx.coroutines.test.StandardTestDispatcher
            import kotlinx.coroutines.test.runTest
            import kotlin.test.Test
            import kotlin.test.assertTrue

            class LoginViewModelGeneratedTest {
                private val testDispatcher = StandardTestDispatcher()

                @Test
                fun `login updates state on success`() = runTest {
                    val repository = FakeLoginRepository()
                    val viewModel = LoginViewModel(repository, testDispatcher)

                    viewModel.onEmailChanged("valid@example.com")
                    viewModel.onPasswordChanged("password123")
                    viewModel.login()

                    val finalState = viewModel.uiState.value
                    assertTrue(finalState.isLoggedIn)
                }
            }
        """.trimIndent()

        val sanitized = sanitizeGeneratedKotlin(content)

        assertTrue("private val testDispatcher = StandardTestDispatcher()" !in sanitized)
        assertContains(sanitized, "LoginViewModel(repository, StandardTestDispatcher(testScheduler))")
        assertContains(sanitized, "viewModel.login()\n        advanceUntilIdle()")
        assertContains(sanitized, "@OptIn(ExperimentalCoroutinesApi::class)")
        assertContains(sanitized, "import kotlinx.coroutines.ExperimentalCoroutinesApi")
        assertContains(sanitized, "import kotlinx.coroutines.test.advanceUntilIdle")
    }

    @Test
    fun `adds common coroutine flow and lifecycle imports used by generated fakes`() {
        val content = """
            package net.ifmain.androiddummy.chatbot.ui

            import kotlinx.coroutines.test.StandardTestDispatcher
            import kotlinx.coroutines.test.runTest
            import kotlin.test.Test
            import kotlin.test.assertEquals

            class NutritionChatViewModelGeneratedTest {
                @Test
                fun `sendMessage success`() = runTest {
                    val flow = MutableSharedFlow<String>()
                    val state = MutableStateFlow("")
                    val shared: SharedFlow<String> = flow.asSharedFlow()
                    val values: StateFlow<String> = state.asStateFlow()
                    values.update { it }
                    flow.first()
                    viewModelScope.launch { }
                    assertEquals(1, 1)
                }
            }
        """.trimIndent()

        val sanitized = sanitizeGeneratedKotlin(content)

        assertContains(sanitized, "import androidx.lifecycle.viewModelScope")
        assertContains(sanitized, "import kotlinx.coroutines.launch")
        assertContains(sanitized, "import kotlinx.coroutines.flow.MutableSharedFlow")
        assertContains(sanitized, "import kotlinx.coroutines.flow.MutableStateFlow")
        assertContains(sanitized, "import kotlinx.coroutines.flow.SharedFlow")
        assertContains(sanitized, "import kotlinx.coroutines.flow.StateFlow")
        assertContains(sanitized, "import kotlinx.coroutines.flow.asSharedFlow")
        assertContains(sanitized, "import kotlinx.coroutines.flow.asStateFlow")
        assertContains(sanitized, "import kotlinx.coroutines.flow.update")
        assertContains(sanitized, "import kotlinx.coroutines.flow.first")
    }

    @Test
    fun `normalizes kotlin test imports to junit4 when module style requires it`() {
        val content = """
            package com.example.auth

            import kotlin.test.Test
            import kotlin.test.assertEquals
            import kotlin.test.assertTrue

            class LoginViewModelGeneratedTest {
                @Test
                fun `login updates state`() {
                    assertEquals(1, 1)
                    assertTrue(true)
                }
            }
        """.trimIndent()

        val sanitized = sanitizeGeneratedKotlin(
            content,
            StyleGuide(assertionStyle = "junit4"),
        )

        assertContains(sanitized, "import org.junit.Test")
        assertContains(sanitized, "import org.junit.Assert.assertEquals")
        assertContains(sanitized, "import org.junit.Assert.assertTrue")
        assertTrue("import kotlin.test.Test" !in sanitized)
    }

    @Test
    fun `extracts first json object when model adds trailing text`() {
        val payload = extractStructuredPayloadFromText(
            """
                {"content":"package com.example\nclass SampleGeneratedTest","warnings":["unsupported async path"]}
                Additional explanation the model should not have emitted.
            """.trimIndent(),
        )

        assertContains(payload.content, "class SampleGeneratedTest")
        assertEquals(listOf("unsupported async path"), payload.warnings)
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

    @Test
    fun `fails closed when ai generation fails in strict mode`() {
        val generator = ResponsesApiTestGenerator(
            config = ResponsesApiConfig(
                apiKey = "sk-local",
                model = "gpt-5",
                baseUrl = "http://127.0.0.1:12345",
            ),
            failureMode = AiFailureMode.FAIL_CLOSED,
            httpClient = fakeHttpClient(
                statusCode = 200,
                body = "{not-json}",
            ),
        )

        val error = assertFailsWith<IllegalStateException> {
            generator.generate(plan(), context(), analysis())
        }

        assertContains(error.message.orEmpty(), "AI generation failed")
    }

    @Test
    fun `falls back to heuristic generation in fallback mode`() {
        val generator = ResponsesApiTestGenerator(
            config = ResponsesApiConfig(
                apiKey = "sk-local",
                model = "gpt-5",
                baseUrl = "http://127.0.0.1:12345",
            ),
            failureMode = AiFailureMode.FALLBACK_TO_HEURISTIC,
            httpClient = fakeHttpClient(
                statusCode = 500,
                body = "upstream error",
            ),
        )

        val bundle = generator.generate(plan(), context(), analysis())

        assertTrue(bundle.files.single().content.contains("class SignUpViewModelGeneratedTest"))
        assertContains(bundle.warnings.joinToString("\n"), "Falling back to heuristic generation.")
    }

    @Test
    fun `falls back to heuristic generation when ai output fails quality gate`() {
        val generator = ChatCompletionsTestGenerator(
            config = ChatCompletionsConfig(
                apiKey = "sk-local",
                model = "qwen3-coder-next-mlx",
                baseUrl = "http://127.0.0.1:12345/v1",
            ),
            failureMode = AiFailureMode.FALLBACK_TO_HEURISTIC,
            httpClient = fakeHttpClient(
                statusCode = 200,
                body = """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "{\"content\":\"package com.example.auth\\n\\nimport kotlinx.coroutines.test.runTest\\n\\nclass SignUpViewModelGeneratedTest {\\n    private class SignUpViewModel\\n}\",\"warnings\":[]}"
                          }
                        }
                      ]
                    }
                """.trimIndent(),
            ),
        )

        val bundle = generator.generate(
            plan(),
            context().copy(styleGuide = StyleGuide(assertionStyle = "junit4", coroutineEntryPoint = "unavailable")),
            analysis(),
        )

        assertContains(bundle.warnings.joinToString("\n"), "AI output failed the quality gate")
        assertContains(bundle.files.single().content, "class SignUpViewModelGeneratedTest")
        assertTrue("private class SignUpViewModel" !in bundle.files.single().content)
    }

    @Test
    fun `heuristic generator respects junit4 only module style`() {
        val bundle = KotlinUnitTestGenerator().generate(
            plan(),
            context().copy(styleGuide = StyleGuide(assertionStyle = "junit4", coroutineEntryPoint = "unavailable")),
            analysis(),
        )

        val content = bundle.files.single().content
        assertContains(content, "import org.junit.Test")
        assertContains(content, "import org.junit.Assert.assertEquals")
        assertTrue("runTest(" !in content)
        assertTrue("StandardTestDispatcher" !in content)
    }

    @Test
    fun `bypasses ai for modules without coroutine test support and direct singleton access`() {
        val analysis = ViewModelAnalysis(
            className = "NutritionChatViewModel",
            packageName = "net.ifmain.androiddummy.chatbot.ui",
            filePath = findRepoRoot().resolve("fixtures/sample-app/app/src/main/java/com/example/auth/LoginViewModel.kt"),
            publicMethods = listOf(
                dev.diff2test.android.core.TargetMethod(
                    name = "sendMessage",
                    signature = "fun sendMessage()",
                    body = "ChatbotService.api.sendMessage(request)",
                    mutatesState = true,
                ),
            ),
        )
        val plan = TestPlan(
            targetClass = "NutritionChatViewModel",
            targetMethods = listOf("sendMessage"),
            testType = TestType.LOCAL_UNIT,
            scenarios = emptyList(),
            requiredFakes = emptyList(),
            assertions = emptyList(),
            riskLevel = RiskLevel.LOW,
        )
        val generator = ChatCompletionsTestGenerator(
            config = ChatCompletionsConfig(
                apiKey = "sk-local",
                model = "qwen3-coder-next-mlx",
                baseUrl = "http://127.0.0.1:12345/v1",
            ),
            httpClient = fakeHttpClient(statusCode = 500, body = "should not be called"),
        )

        val bundle = generator.generate(
            plan = plan,
            context = TestContext(
                moduleName = "app",
                styleGuide = StyleGuide(assertionStyle = "junit4", coroutineEntryPoint = "unavailable"),
            ),
            analysis = analysis,
        )

        assertContains(bundle.warnings.joinToString("\n"), "AI generation was skipped")
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

    private fun analysis(): ViewModelAnalysis {
        return ViewModelAnalysis(
            className = "SignUpViewModel",
            packageName = "com.example.auth",
            filePath = findRepoRoot().resolve("fixtures/sample-app/app/src/main/java/com/example/auth/SignUpViewModel.kt"),
        )
    }

    private fun plan(): TestPlan {
        return TestPlan(
            targetClass = "SignUpViewModel",
            targetMethods = listOf("submitRegistration"),
            testType = TestType.LOCAL_UNIT,
            scenarios = listOf(
                TestScenario(
                    name = "submitRegistration updates state on success",
                    goal = "happy path",
                    expectedOutcome = "observable state reflects success",
                ),
            ),
            requiredFakes = emptyList(),
            assertions = emptyList(),
            riskLevel = RiskLevel.LOW,
        )
    }

    private fun context(): TestContext {
        return TestContext(
            moduleName = "app",
            styleGuide = StyleGuide(),
        )
    }

    private fun fakeHttpClient(statusCode: Int, body: String): HttpClient {
        return object : HttpClient() {
            override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
            override fun connectTimeout(): Optional<Duration> = Optional.empty()
            override fun followRedirects(): Redirect = Redirect.NEVER
            override fun proxy(): Optional<ProxySelector> = Optional.empty()
            override fun sslContext(): SSLContext = SSLContext.getDefault()
            override fun sslParameters(): SSLParameters = SSLParameters()
            override fun authenticator(): Optional<Authenticator> = Optional.empty()
            override fun version(): Version = Version.HTTP_1_1
            override fun executor(): Optional<Executor> = Optional.empty()
            override fun <T : Any?> send(
                request: HttpRequest?,
                responseBodyHandler: HttpResponse.BodyHandler<T>?,
            ): HttpResponse<T> {
                @Suppress("UNCHECKED_CAST")
                return fakeResponse(statusCode, body) as HttpResponse<T>
            }

            override fun <T : Any?> sendAsync(
                request: HttpRequest?,
                responseBodyHandler: HttpResponse.BodyHandler<T>?,
            ): CompletableFuture<HttpResponse<T>> {
                error("Not used in tests")
            }

            override fun <T : Any?> sendAsync(
                request: HttpRequest?,
                responseBodyHandler: HttpResponse.BodyHandler<T>?,
                pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
            ): CompletableFuture<HttpResponse<T>> {
                error("Not used in tests")
            }
        }
    }

    private fun fakeResponse(statusCode: Int, body: String): HttpResponse<String> {
        return object : HttpResponse<String> {
            override fun statusCode(): Int = statusCode
            override fun request(): HttpRequest = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:12345/responses")).build()
            override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
            override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
            override fun body(): String = body
            override fun sslSession(): Optional<SSLSession> = Optional.empty()
            override fun uri(): URI = URI.create("http://127.0.0.1:12345/responses")
            override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
        }
    }
}
