package dev.diff2test.android.testgenerator

import dev.diff2test.android.core.GeneratedFile
import dev.diff2test.android.core.GeneratedTestBundle
import dev.diff2test.android.core.StyleGuide
import dev.diff2test.android.core.TestContext
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.core.ViewModelAnalysis
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ResponsesApiConfig(
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val reasoningEffort: String? = null,
    val connectTimeoutSeconds: Long = 30,
    val requestTimeoutSeconds: Long = 180,
)

data class ChatCompletionsConfig(
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val connectTimeoutSeconds: Long = 30,
    val requestTimeoutSeconds: Long = 180,
)

data class AnthropicMessagesConfig(
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val maxTokens: Int = 4_096,
    val connectTimeoutSeconds: Long = 30,
    val requestTimeoutSeconds: Long = 180,
)

data class GeminiGenerateContentConfig(
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val connectTimeoutSeconds: Long = 30,
    val requestTimeoutSeconds: Long = 180,
)

enum class AiFailureMode {
    FALLBACK_TO_HEURISTIC,
    FAIL_CLOSED,
}

fun responsesApiConfigFromEnvironment(modelOverride: String? = null): ResponsesApiConfig? {
    return responsesApiConfigFromEnvironment(
        environment = System.getenv(),
        modelOverride = modelOverride,
    )
}

internal fun responsesApiConfigFromEnvironment(
    environment: Map<String, String>,
    modelOverride: String? = null,
): ResponsesApiConfig? {
    val apiKey = firstDefinedValue(
        environment,
        "D2T_AI_AUTH_TOKEN",
        "LLM_API_KEY",
        "ANTHROPIC_AUTH_TOKEN",
        "D2T_OPENAI_API_KEY",
        "OPENAI_API_KEY",
    ).orEmpty()
    if (apiKey.isBlank()) {
        return null
    }

    val model = modelOverride
        ?: firstDefinedValue(
            environment,
            "D2T_AI_MODEL",
            "STRIX_LLM",
            "LLM_MODEL",
            "ANTHROPIC_MODEL",
            "ANTHROPIC_SMALL_FAST_MODEL",
            "D2T_OPENAI_MODEL",
            "OPENAI_MODEL",
        )
        ?: "gpt-5"
    val baseUrl = firstDefinedValue(
        environment,
        "D2T_AI_BASE_URL",
        "LLM_API_BASE",
        "ANTHROPIC_BASE_URL",
        "D2T_OPENAI_BASE_URL",
        "OPENAI_BASE_URL",
    )
        ?: "https://api.openai.com/v1"
    val reasoningEffort = firstDefinedValue(
        environment,
        "D2T_REASONING_EFFORT",
        "STRIX_RESONING_EFFORT",
        "STRIX_REASONING_EFFORT",
        "OPENAI_REASONING_EFFORT",
    )
    val connectTimeoutSeconds = firstDefinedValue(
        environment,
        "D2T_CONNECT_TIMEOUT_SECONDS",
        "LLM_CONNECT_TIMEOUT_SECONDS",
        "OPENAI_CONNECT_TIMEOUT_SECONDS",
    )?.toLongOrNull() ?: 30L
    val requestTimeoutSeconds = firstDefinedValue(
        environment,
        "D2T_REQUEST_TIMEOUT_SECONDS",
        "LLM_REQUEST_TIMEOUT_SECONDS",
        "OPENAI_REQUEST_TIMEOUT_SECONDS",
    )?.toLongOrNull() ?: 180L

    return ResponsesApiConfig(
        apiKey = apiKey,
        model = model,
        baseUrl = baseUrl.removeSuffix("/"),
        reasoningEffort = reasoningEffort?.trim()?.ifBlank { null },
        connectTimeoutSeconds = connectTimeoutSeconds,
        requestTimeoutSeconds = requestTimeoutSeconds,
    )
}

class ResponsesApiTestGenerator(
    private val config: ResponsesApiConfig,
    private val fallback: TestGenerator = KotlinUnitTestGenerator(),
    private val failureMode: AiFailureMode = AiFailureMode.FALLBACK_TO_HEURISTIC,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds))
        .version(HttpClient.Version.HTTP_1_1)
        .build(),
) : TestGenerator {
    override fun generate(
        plan: TestPlan,
        context: TestContext,
        analysis: ViewModelAnalysis,
    ): GeneratedTestBundle {
        maybeBypassAi(plan, context, analysis, fallback)?.let { return it }
        val startedAt = System.nanoTime()
        return try {
            val promptSpec = buildPromptSpec(plan, context, analysis)
            val requestBody = buildResponsesRequest(promptSpec.instructions, promptSpec.input)
            logAiProgress(
                "request started: model=${config.model}, url=${config.baseUrl}/responses, " +
                    "instructions=${promptSpec.instructions.length} chars, input=${promptSpec.input.length} chars, " +
                    "body=${requestBody.length} chars, timeout=${config.requestTimeoutSeconds}s",
            )
            val responseBody = executeResponsesRequest(requestBody)
            logAiProgress(
                "response received: ${responseBody.length} chars in ${elapsedMillis(startedAt)}ms; parsing structured payload",
            )
            val payload = extractStructuredPayload(responseBody)
            logAiProgress(
                "structured payload parsed: warnings=${payload.warnings.size}, output=${payload.content.length} chars",
            )

            val generatedBundle = GeneratedTestBundle(
                plan = plan,
                files = listOf(
                    GeneratedFile(
                        relativePath = generatedTestRelativePath(plan, analysis),
                        content = sanitizeGeneratedKotlin(payload.content, context.styleGuide),
                    ),
                ),
                warnings = payload.warnings,
            )
            validateOrFallback(
                generatedBundle = generatedBundle,
                plan = plan,
                context = context,
                analysis = analysis,
                startedAt = startedAt,
                fallback = fallback,
                failureMode = failureMode,
            )
        } catch (error: Exception) {
            logAiProgress(
                "request failed after ${elapsedMillis(startedAt)}ms: ${error.message ?: error::class.simpleName}",
            )
            if (failureMode == AiFailureMode.FAIL_CLOSED) {
                throw IllegalStateException(
                    "AI generation failed: ${error.message ?: error::class.simpleName}",
                    error,
                )
            }
            val fallbackBundle = fallback.generate(plan, context, analysis)
            fallbackBundle.copy(
                warnings = listOf(
                    "AI generation failed: ${error.message ?: error::class.simpleName}. Falling back to heuristic generation.",
                ) + fallbackBundle.warnings,
            )
        }
    }

    private fun buildResponsesRequest(instructions: String, input: String): String {
        return buildResponsesRequest(config, instructions, input)
    }

    private fun executeResponsesRequest(requestBody: String): String {
        logAiProgress("waiting for response...")
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.baseUrl}/responses"))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${config.apiKey}")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Responses API request failed with HTTP ${response.statusCode()}: ${response.body()}"
        }
        return response.body()
    }
}

class ChatCompletionsTestGenerator(
    private val config: ChatCompletionsConfig,
    private val fallback: TestGenerator = KotlinUnitTestGenerator(),
    private val failureMode: AiFailureMode = AiFailureMode.FALLBACK_TO_HEURISTIC,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds))
        .version(HttpClient.Version.HTTP_1_1)
        .build(),
) : TestGenerator {
    override fun generate(
        plan: TestPlan,
        context: TestContext,
        analysis: ViewModelAnalysis,
    ): GeneratedTestBundle {
        maybeBypassAi(plan, context, analysis, fallback)?.let { return it }
        val startedAt = System.nanoTime()
        return try {
            val promptSpec = buildPromptSpec(plan, context, analysis)
            val requestBody = buildChatCompletionsRequest(config, promptSpec.instructions, promptSpec.input)
            logAiProgress(
                "request started: model=${config.model}, url=${config.baseUrl}/chat/completions, " +
                    "instructions=${promptSpec.instructions.length} chars, input=${promptSpec.input.length} chars, " +
                    "body=${requestBody.length} chars, timeout=${config.requestTimeoutSeconds}s",
            )
            val responseBody = executeChatCompletionsRequest(requestBody)
            logAiProgress(
                "response received: ${responseBody.length} chars in ${elapsedMillis(startedAt)}ms; parsing structured payload",
            )
            val payload = extractChatCompletionsStructuredPayload(responseBody)
            logAiProgress(
                "structured payload parsed: warnings=${payload.warnings.size}, output=${payload.content.length} chars",
            )

            val generatedBundle = GeneratedTestBundle(
                plan = plan,
                files = listOf(
                    GeneratedFile(
                        relativePath = generatedTestRelativePath(plan, analysis),
                        content = sanitizeGeneratedKotlin(payload.content, context.styleGuide),
                    ),
                ),
                warnings = payload.warnings,
            )
            validateOrFallback(
                generatedBundle = generatedBundle,
                plan = plan,
                context = context,
                analysis = analysis,
                startedAt = startedAt,
                fallback = fallback,
                failureMode = failureMode,
            )
        } catch (error: Exception) {
            logAiProgress(
                "request failed after ${elapsedMillis(startedAt)}ms: ${error.message ?: error::class.simpleName}",
            )
            if (failureMode == AiFailureMode.FAIL_CLOSED) {
                throw IllegalStateException(
                    "AI generation failed: ${error.message ?: error::class.simpleName}",
                    error,
                )
            }
            val fallbackBundle = fallback.generate(plan, context, analysis)
            fallbackBundle.copy(
                warnings = listOf(
                    "AI generation failed: ${error.message ?: error::class.simpleName}. Falling back to heuristic generation.",
                ) + fallbackBundle.warnings,
            )
        }
    }

    private fun executeChatCompletionsRequest(requestBody: String): String {
        logAiProgress("waiting for response...")
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.baseUrl}/chat/completions"))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${config.apiKey}")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Chat Completions request failed with HTTP ${response.statusCode()}: ${response.body()}"
        }
        return response.body()
    }
}

class AnthropicMessagesTestGenerator(
    private val config: AnthropicMessagesConfig,
    private val fallback: TestGenerator = KotlinUnitTestGenerator(),
    private val failureMode: AiFailureMode = AiFailureMode.FALLBACK_TO_HEURISTIC,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds))
        .version(HttpClient.Version.HTTP_1_1)
        .build(),
) : TestGenerator {
    override fun generate(
        plan: TestPlan,
        context: TestContext,
        analysis: ViewModelAnalysis,
    ): GeneratedTestBundle {
        maybeBypassAi(plan, context, analysis, fallback)?.let { return it }
        val startedAt = System.nanoTime()
        return try {
            val promptSpec = buildPromptSpec(plan, context, analysis)
            val requestBody = buildAnthropicMessagesRequest(config, promptSpec.instructions, promptSpec.input)
            logAiProgress(
                "request started: model=${config.model}, url=${config.baseUrl}/messages, " +
                    "instructions=${promptSpec.instructions.length} chars, input=${promptSpec.input.length} chars, " +
                    "body=${requestBody.length} chars, timeout=${config.requestTimeoutSeconds}s",
            )
            val responseBody = executeAnthropicMessagesRequest(requestBody)
            logAiProgress(
                "response received: ${responseBody.length} chars in ${elapsedMillis(startedAt)}ms; parsing structured payload",
            )
            val payload = extractAnthropicStructuredPayload(responseBody)
            logAiProgress(
                "structured payload parsed: warnings=${payload.warnings.size}, output=${payload.content.length} chars",
            )

            val generatedBundle = GeneratedTestBundle(
                plan = plan,
                files = listOf(
                    GeneratedFile(
                        relativePath = generatedTestRelativePath(plan, analysis),
                        content = sanitizeGeneratedKotlin(payload.content, context.styleGuide),
                    ),
                ),
                warnings = payload.warnings,
            )
            validateOrFallback(
                generatedBundle = generatedBundle,
                plan = plan,
                context = context,
                analysis = analysis,
                startedAt = startedAt,
                fallback = fallback,
                failureMode = failureMode,
            )
        } catch (error: Exception) {
            logAiProgress(
                "request failed after ${elapsedMillis(startedAt)}ms: ${error.message ?: error::class.simpleName}",
            )
            if (failureMode == AiFailureMode.FAIL_CLOSED) {
                throw IllegalStateException(
                    "AI generation failed: ${error.message ?: error::class.simpleName}",
                    error,
                )
            }
            val fallbackBundle = fallback.generate(plan, context, analysis)
            fallbackBundle.copy(
                warnings = listOf(
                    "AI generation failed: ${error.message ?: error::class.simpleName}. Falling back to heuristic generation.",
                ) + fallbackBundle.warnings,
            )
        }
    }

    private fun executeAnthropicMessagesRequest(requestBody: String): String {
        logAiProgress("waiting for response...")
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.baseUrl}/messages"))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Anthropic Messages request failed with HTTP ${response.statusCode()}: ${response.body()}"
        }
        return response.body()
    }
}

class GeminiGenerateContentTestGenerator(
    private val config: GeminiGenerateContentConfig,
    private val fallback: TestGenerator = KotlinUnitTestGenerator(),
    private val failureMode: AiFailureMode = AiFailureMode.FALLBACK_TO_HEURISTIC,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds))
        .version(HttpClient.Version.HTTP_1_1)
        .build(),
) : TestGenerator {
    override fun generate(
        plan: TestPlan,
        context: TestContext,
        analysis: ViewModelAnalysis,
    ): GeneratedTestBundle {
        maybeBypassAi(plan, context, analysis, fallback)?.let { return it }
        val startedAt = System.nanoTime()
        return try {
            val promptSpec = buildPromptSpec(plan, context, analysis)
            val requestBody = buildGeminiGenerateContentRequest(config, promptSpec.instructions, promptSpec.input)
            logAiProgress(
                "request started: model=${config.model}, url=${config.baseUrl}/models/${config.model}:generateContent, " +
                    "instructions=${promptSpec.instructions.length} chars, input=${promptSpec.input.length} chars, " +
                    "body=${requestBody.length} chars, timeout=${config.requestTimeoutSeconds}s",
            )
            val responseBody = executeGeminiGenerateContentRequest(requestBody)
            logAiProgress(
                "response received: ${responseBody.length} chars in ${elapsedMillis(startedAt)}ms; parsing structured payload",
            )
            val payload = extractGeminiStructuredPayload(responseBody)
            logAiProgress(
                "structured payload parsed: warnings=${payload.warnings.size}, output=${payload.content.length} chars",
            )

            val generatedBundle = GeneratedTestBundle(
                plan = plan,
                files = listOf(
                    GeneratedFile(
                        relativePath = generatedTestRelativePath(plan, analysis),
                        content = sanitizeGeneratedKotlin(payload.content, context.styleGuide),
                    ),
                ),
                warnings = payload.warnings,
            )
            validateOrFallback(
                generatedBundle = generatedBundle,
                plan = plan,
                context = context,
                analysis = analysis,
                startedAt = startedAt,
                fallback = fallback,
                failureMode = failureMode,
            )
        } catch (error: Exception) {
            logAiProgress(
                "request failed after ${elapsedMillis(startedAt)}ms: ${error.message ?: error::class.simpleName}",
            )
            if (failureMode == AiFailureMode.FAIL_CLOSED) {
                throw IllegalStateException(
                    "AI generation failed: ${error.message ?: error::class.simpleName}",
                    error,
                )
            }
            val fallbackBundle = fallback.generate(plan, context, analysis)
            fallbackBundle.copy(
                warnings = listOf(
                    "AI generation failed: ${error.message ?: error::class.simpleName}. Falling back to heuristic generation.",
                ) + fallbackBundle.warnings,
            )
        }
    }

    private fun executeGeminiGenerateContentRequest(requestBody: String): String {
        logAiProgress("waiting for response...")
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.baseUrl}/models/${config.model}:generateContent"))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("x-goog-api-key", config.apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Gemini GenerateContent request failed with HTTP ${response.statusCode()}: ${response.body()}"
        }
        return response.body()
    }
}

private fun logAiProgress(message: String) {
    println("[ai] $message")
}

private fun elapsedMillis(startedAt: Long): Long {
    return (System.nanoTime() - startedAt) / 1_000_000
}

internal fun buildResponsesRequest(
    config: ResponsesApiConfig,
    instructions: String,
    input: String,
): String {
    return buildJsonObject {
        put("model", config.model)
        put("store", false)
        put("instructions", instructions)
        put("input", input)
        put(
            "text",
            buildJsonObject {
                put(
                    "format",
                    buildJsonObject {
                        put("type", "json_schema")
                        put("name", "generated_viewmodel_test")
                        put("strict", true)
                        put("schema", structuredPayloadSchema())
                    },
                )
            },
        )
        if (config.reasoningEffort != null) {
            put(
                "reasoning",
                buildJsonObject {
                    put("effort", config.reasoningEffort)
                },
            )
        }
    }.toString()
}

internal fun buildAnthropicMessagesRequest(
    config: AnthropicMessagesConfig,
    instructions: String,
    input: String,
): String {
    return buildJsonObject {
        put("model", config.model)
        put("max_tokens", config.maxTokens)
        put("temperature", 0)
        put("system", "$instructions\nReturn valid JSON only.")
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", input)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }.toString()
}

internal fun buildChatCompletionsRequest(
    config: ChatCompletionsConfig,
    instructions: String,
    input: String,
): String {
    val systemPrompt = buildString {
        appendLine(instructions)
        appendLine()
        appendLine("Return valid JSON only.")
        appendLine("""Use this JSON shape exactly: {"content":"<kotlin source>","warnings":["<warning>"]}""")
        appendLine("Do not wrap the JSON in Markdown fences.")
    }.trim()

    return buildJsonObject {
        put("model", config.model)
        put("temperature", 0)
        put(
            "response_format",
            buildJsonObject {
                put("type", "json_schema")
                put(
                    "json_schema",
                    buildJsonObject {
                        put("name", "generated_viewmodel_test")
                        put("strict", true)
                        put("schema", structuredPayloadSchema())
                    },
                )
            },
        )
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    },
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", input)
                    },
                )
            },
        )
    }.toString()
}

@Suppress("UNUSED_PARAMETER")
internal fun buildGeminiGenerateContentRequest(
    config: GeminiGenerateContentConfig,
    instructions: String,
    input: String,
): String {
    return buildJsonObject {
        put(
            "system_instruction",
            buildJsonObject {
                put(
                    "parts",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("text", "$instructions\nReturn valid JSON only.")
                            },
                        )
                    },
                )
            },
        )
        put(
            "contents",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "parts",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("text", input)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        put(
            "generationConfig",
            buildJsonObject {
                put("responseMimeType", "application/json")
                put("responseSchema", geminiStructuredPayloadSchema())
            },
        )
    }.toString()
}

internal data class AiPromptSpec(
    val instructions: String,
    val input: String,
)

internal data class StructuredTestPayload(
    val content: String,
    val warnings: List<String>,
)

internal fun buildPromptSpec(
    plan: TestPlan,
    context: TestContext,
    analysis: ViewModelAnalysis,
): AiPromptSpec {
    val workspaceRoot = findWorkspaceRoot(analysis.filePath)
    val promptFile = workspaceRoot.resolve("prompts/generator/generate-coroutine-viewmodel-tests.md")
    val promptTemplate = if (Files.exists(promptFile)) {
        Files.readString(promptFile).trim()
    } else {
        "Generate Kotlin tests from a TestPlan."
    }
    val sourceText = Files.readString(analysis.filePath)
    val dependencySources = loadDependencySources(analysis, sourceText)
    val targetClassName = "${analysis.className}GeneratedTest"
    val targetRelativePath = generatedTestRelativePath(plan, analysis)

    val instructions = buildString {
        appendLine(promptTemplate)
        appendLine()
        appendLine("You are generating a compile-ready Kotlin local unit test file for an Android ViewModel.")
        appendLine("Return JSON only. Do not use markdown fences.")
        appendLine("The JSON must contain `content` and `warnings`.")
        appendLine("The `content` must be a full Kotlin file for `$targetClassName`.")
        appendLine("Target file path: $targetRelativePath")
        appendLine("Prefer constructor-injected fakes over Android runtime dependencies.")
        appendLine("If information is missing, prefer focused fake implementations and record caveats in `warnings` instead of inventing APIs.")
        appendLine("When dependency source is provided, preserve its method names, parameter lists, and exact return types in all stubs and mocks.")
        if (context.styleGuide.assertionStyle == "junit4") {
            appendLine("Use `org.junit.Test` and `org.junit.Assert.*` assertions. Do not use `kotlin.test`.")
        } else {
            appendLine("Use `kotlin.test.Test` and `kotlin.test` assertions. Do not use `org.junit.Test`.")
        }
        if (context.styleGuide.coroutineEntryPoint == "runTest") {
            appendLine("Use `runTest`, `StandardTestDispatcher`, and `advanceUntilIdle` when coroutine control is needed.")
        } else {
            appendLine("This module does not declare `kotlinx-coroutines-test`.")
            appendLine("Do not use `runTest`, `StandardTestDispatcher`, `testScheduler`, or `advanceUntilIdle`.")
            appendLine("Only generate compile-safe tests that rely on the existing project test stack.")
            appendLine("If async behavior requires coroutine-test support or injectable dispatchers, record that as a warning and skip the unsupported scenario.")
        }
        appendLine("Do not create a nested class with the same name as the target ViewModel.")
        appendLine("Do not subclass the target ViewModel or override its members unless the source explicitly declares the class and members as open.")
        appendLine("If the ViewModel has no injectable collaborators and directly accesses global singletons or objects, do not invent injectable seams.")
        appendLine("In that case, only test compile-safe public behavior and explain unsupported scenarios in `warnings`.")
        appendLine("Never reference nested collaborator types that do not appear in the provided source, such as inventing `SomeObject.Api` when only a top-level interface exists.")
    }

    val input = buildString {
        appendLine("Generate a Kotlin test file for this ViewModel.")
        appendLine()
        appendLine("## Target")
        appendLine("className: ${analysis.className}")
        appendLine("packageName: ${analysis.packageName}")
        appendLine("filePath: ${analysis.filePath}")
        appendLine("generatedTestClass: $targetClassName")
        appendLine("generatedRelativePath: $targetRelativePath")
        appendLine()
        appendLine("## TestPlan")
        appendLine("testType: ${plan.testType}")
        appendLine("riskLevel: ${plan.riskLevel}")
        appendLine("targetMethods: ${plan.targetMethods.joinToString()}")
        appendLine("scenarios:")
        plan.scenarios.forEach { scenario ->
            appendLine("- ${scenario.name}: ${scenario.goal} | expected=${scenario.expectedOutcome}")
        }
        appendLine()
        appendLine("## StyleGuide")
        appendLine("mockLibrary: ${context.styleGuide.mockLibrary}")
        appendLine("assertionStyle: ${context.styleGuide.assertionStyle}")
        appendLine("coroutineEntryPoint: ${context.styleGuide.coroutineEntryPoint}")
        appendLine("flowProbe: ${context.styleGuide.flowProbe}")
        appendLine("namingPattern: ${context.styleGuide.namingPattern}")
        appendLine("existingPatterns:")
        context.existingTestPatterns.forEach { pattern ->
            appendLine("- $pattern")
        }
        appendLine()
        appendLine("## Analysis")
        appendLine("constructorDependencies:")
        analysis.constructorDependencies.forEach { dependency ->
            appendLine("- ${dependency.name}: ${dependency.type} (${dependency.role ?: "collaborator"})")
        }
        appendLine("stateHolders: ${analysis.stateHolders.joinToString()}")
        appendLine("primaryStateHolderName: ${analysis.primaryStateHolderName}")
        appendLine("primaryStateType: ${analysis.primaryStateType}")
        appendLine("androidFrameworkTouchpoints: ${analysis.androidFrameworkTouchpoints.joinToString()}")
        appendLine("notes:")
        analysis.notes.forEach { note ->
            appendLine("- $note")
        }
        appendLine("methods:")
        analysis.publicMethods.forEach { method ->
            appendLine("- ${method.signature}")
            val methodBody = method.body
            if (!methodBody.isNullOrBlank()) {
                appendLine("  body:")
                methodBody.lines().forEach { line ->
                    appendLine("    $line")
                }
            }
        }
        appendLine()
        appendLine("## Source")
        appendLine("```kotlin")
        appendLine(sourceText.trimEnd())
        appendLine("```")
        if (dependencySources.isNotEmpty()) {
            appendLine()
            appendLine("## Dependency Sources")
            dependencySources.forEach { dependencySource ->
                appendLine("### ${dependencySource.first}")
                appendLine("```kotlin")
                appendLine(dependencySource.second.trimEnd())
                appendLine("```")
            }
        }
        appendLine()
        appendLine("## Output requirements")
        appendLine("- use package `${analysis.packageName}`")
        appendLine("- class name must be `$targetClassName`")
        if (context.styleGuide.coroutineEntryPoint == "runTest") {
            appendLine("- prefer `runTest` and `StandardTestDispatcher`")
        } else {
            appendLine("- do not use kotlinx.coroutines.test APIs because the module does not declare them")
        }
        appendLine("- assert observable state or event outcomes")
        appendLine("- include at least one negative-path test if the source shows validation or failure handling")
        appendLine("- do not emit placeholder `assertTrue(true)` tests")
        appendLine("- do not emit markdown fences in `content`")
        appendLine("- do not redefine or subclass `${analysis.className}` inside the generated test file")
    }

    return AiPromptSpec(
        instructions = instructions.trim(),
        input = input.trim(),
    )
}

private fun loadDependencySources(
    analysis: ViewModelAnalysis,
    sourceText: String,
): List<Pair<String, String>> {
    val moduleRoot = inferModuleRootFromTarget(analysis.filePath)
    val dependencySources = linkedMapOf<String, String>()
    analysis.constructorDependencies.forEach { dependency ->
        val sourceFile = resolveTypeFile(moduleRoot, dependency.type) ?: return@forEach
        dependencySources.putIfAbsent(dependency.type, Files.readString(sourceFile))
    }
    loadReferencedLocalSources(moduleRoot, analysis, sourceText).forEach { (typeName, contents) ->
        dependencySources.putIfAbsent(typeName, contents)
    }
    return dependencySources.entries.map { it.toPair() }
}

private fun loadReferencedLocalSources(
    moduleRoot: Path,
    analysis: ViewModelAnalysis,
    sourceText: String,
): List<Pair<String, String>> {
    val packagePrefix = analysis.packageName.substringBeforeLast('.', missingDelimiterValue = analysis.packageName)
    return sourceText.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("import ") }
        .map { it.removePrefix("import ").trim() }
        .filter { importName ->
            importName.isNotBlank() &&
                !importName.startsWith("android.") &&
                !importName.startsWith("androidx.") &&
                !importName.startsWith("java.") &&
                !importName.startsWith("javax.") &&
                !importName.startsWith("kotlin.") &&
                !importName.startsWith("kotlinx.") &&
                !importName.startsWith("retrofit2.") &&
                !importName.startsWith("okhttp3.") &&
                (packagePrefix.isBlank() || importName.startsWith(packagePrefix))
        }
        .mapNotNull { importName ->
            val typeName = importName.substringAfterLast('.')
            val sourceFile = resolveTypeFile(moduleRoot, typeName) ?: return@mapNotNull null
            if (sourceFile == analysis.filePath) return@mapNotNull null
            typeName to Files.readString(sourceFile)
        }
        .toList()
}

internal fun extractStructuredPayload(responseBody: String): StructuredTestPayload {
    val root = Json.parseToJsonElement(responseBody).jsonObject
    val text = root["output_text"]?.jsonPrimitive?.contentOrNull
        ?: root["output"]?.jsonArray?.firstNotNullOfOrNull(::extractOutputText)
        ?: error("OpenAI response did not include output text.")

    return extractStructuredPayloadFromText(text)
}

internal fun extractAnthropicStructuredPayload(responseBody: String): StructuredTestPayload {
    val root = Json.parseToJsonElement(responseBody).jsonObject
    val text = root["content"]?.jsonArray
        ?.firstNotNullOfOrNull { item ->
            val itemObject = item.jsonObject
            if (itemObject["type"]?.jsonPrimitive?.contentOrNull == "text") {
                itemObject["text"]?.jsonPrimitive?.contentOrNull
            } else {
                null
            }
        }
        ?: error("Anthropic response did not include text content.")

    return extractStructuredPayloadFromText(text)
}

internal fun extractChatCompletionsStructuredPayload(responseBody: String): StructuredTestPayload {
    val parsed = Json.parseToJsonElement(responseBody)
    val text = parsed.jsonObject["choices"]
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
        ?.get("message")
        ?.jsonObject
        ?.get("content")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?: error("Chat Completions response did not include message content.")

    return extractStructuredPayloadFromText(text)
}

internal fun extractGeminiStructuredPayload(responseBody: String): StructuredTestPayload {
    val root = Json.parseToJsonElement(responseBody).jsonObject
    val text = root["candidates"]?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
        ?.get("content")
        ?.jsonObject
        ?.get("parts")
        ?.jsonArray
        ?.firstNotNullOfOrNull { part ->
            part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        }
        ?: error("Gemini response did not include text content.")

    return extractStructuredPayloadFromText(text)
}

internal fun extractStructuredPayloadFromText(text: String): StructuredTestPayload {
    val normalizedText = sanitizeGeneratedJson(extractLikelyJsonObject(text))

    val payload = Json.parseToJsonElement(normalizedText).jsonObject
    val content = payload["content"]?.jsonPrimitive?.contentOrNull
        ?: error("Structured payload did not include `content`.")
    val warnings = payload["warnings"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?: emptyList()

    return StructuredTestPayload(
        content = sanitizeGeneratedKotlin(content),
        warnings = warnings,
    )
}

internal fun extractLikelyJsonObject(text: String): String {
    val trimmed = text.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        return trimmed
    }

    val start = trimmed.indexOf('{')
    if (start < 0) {
        return trimmed
    }

    var depth = 0
    var inString = false
    var escaping = false
    for (index in start until trimmed.length) {
        val char = trimmed[index]
        if (escaping) {
            escaping = false
            continue
        }
        when {
            char == '\\' && inString -> escaping = true
            char == '"' -> inString = !inString
            !inString && char == '{' -> depth += 1
            !inString && char == '}' -> {
                depth -= 1
                if (depth == 0) {
                    return trimmed.substring(start, index + 1)
                }
            }
        }
    }

    return trimmed
}

internal fun structuredPayloadSchema() = buildJsonObject {
    put("type", "object")
    put(
        "properties",
        buildJsonObject {
            put(
                "content",
                buildJsonObject {
                    put("type", "string")
                    put("minLength", 1)
                },
            )
            put(
                "warnings",
                buildJsonObject {
                    put("type", "array")
                    put(
                        "items",
                        buildJsonObject {
                            put("type", "string")
                        },
                    )
                },
            )
        },
    )
    put(
        "required",
        buildJsonArray {
            add(JsonPrimitive("content"))
            add(JsonPrimitive("warnings"))
        },
    )
    put("additionalProperties", false)
}

internal fun geminiStructuredPayloadSchema() = stripAdditionalProperties(structuredPayloadSchema())

private fun stripAdditionalProperties(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
    return when (element) {
        is kotlinx.serialization.json.JsonObject ->
            buildJsonObject {
                element.forEach { (key, value) ->
                    if (key != "additionalProperties") {
                        put(key, stripAdditionalProperties(value))
                    }
                }
            }

        is kotlinx.serialization.json.JsonArray ->
            buildJsonArray {
                element.forEach { add(stripAdditionalProperties(it)) }
            }

        else -> element
    }
}

internal fun generatedTestRelativePath(plan: TestPlan, analysis: ViewModelAnalysis): Path {
    val packageName = analysis.packageName.ifBlank { "dev.diff2test.android.generated" }
    return Path.of(
        "src/test/kotlin/" + packageName.replace('.', '/') + "/${plan.targetClass}GeneratedTest.kt",
    )
}

private fun extractOutputText(item: kotlinx.serialization.json.JsonElement): String? {
    val content = item.jsonObject["content"]?.jsonArray ?: return null
    return content.firstNotNullOfOrNull { contentItem ->
        contentItem.jsonObject["text"]?.jsonPrimitive?.contentOrNull
    }
}

internal fun sanitizeGeneratedKotlin(content: String, styleGuide: StyleGuide = StyleGuide()): String {
    val trimmed = content.trim()
    val sanitized = if (trimmed.startsWith("```")) {
        trimmed
            .removePrefix("```kotlin")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    } else {
        trimmed
    }
    val normalizedAssertions = normalizeAssertionStyle(
        sanitized
            .replace("import kotlinx.coroutines.runTest\n", "")
            .replace("this@TestScope", "testScheduler"),
        styleGuide,
    )

    return ensureGeneratedImports(
        normalizeImports(
            ensureExperimentalCoroutinesOptIn(
                stabilizeCoroutineTestPatterns(
                    normalizedAssertions,
                ),
            ),
        ),
    )
}

private fun AiFailureMode.shouldFailClosed(): Boolean = this == AiFailureMode.FAIL_CLOSED

private fun <T> elapsedFailure(startedAt: Long, message: String, error: Exception): Nothing {
    logAiProgress("request failed after ${elapsedMillis(startedAt)}ms: $message")
    throw IllegalStateException(message, error)
}

private val DIRECT_SINGLETON_ACCESS_PATTERN = Regex("""\b[A-Z][A-Za-z0-9_]*\.[a-z][A-Za-z0-9_]*""")

private fun maybeBypassAi(
    plan: TestPlan,
    context: TestContext,
    analysis: ViewModelAnalysis,
    fallback: TestGenerator,
): GeneratedTestBundle? {
    val usesDirectSingleton = analysis.publicMethods.any { method ->
        DIRECT_SINGLETON_ACCESS_PATTERN.containsMatchIn(method.body.orEmpty())
    } || runCatching {
        Files.exists(analysis.filePath) && DIRECT_SINGLETON_ACCESS_PATTERN.containsMatchIn(Files.readString(analysis.filePath))
    }.getOrDefault(false)
    if (context.styleGuide.coroutineEntryPoint == "runTest" || !usesDirectSingleton) {
        return null
    }

    val fallbackBundle = fallback.generate(plan, context, analysis)
    return fallbackBundle.copy(
        warnings = listOf(
            "AI generation was skipped because the module does not declare kotlinx-coroutines-test and the ViewModel directly accesses global singleton collaborators. Heuristic generation was used instead.",
        ) + fallbackBundle.warnings,
    )
}

private fun validateOrFallback(
    generatedBundle: GeneratedTestBundle,
    plan: TestPlan,
    context: TestContext,
    analysis: ViewModelAnalysis,
    startedAt: Long,
    fallback: TestGenerator = KotlinUnitTestGenerator(),
    failureMode: AiFailureMode = AiFailureMode.FALLBACK_TO_HEURISTIC,
): GeneratedTestBundle {
    val qualityReport = GeneratedTestQualityGate().evaluate(generatedBundle, context.styleGuide)
    if (qualityReport.passed) {
        return generatedBundle
    }

    val summary = qualityReport.issues.joinToString("; ")
    logAiProgress("quality gate rejected AI output after ${elapsedMillis(startedAt)}ms: $summary")

    if (failureMode.shouldFailClosed()) {
        throw IllegalStateException(
            buildString {
                appendLine("AI generation failed the quality gate.")
                qualityReport.issues.forEach { appendLine("- $it") }
            }.trimEnd(),
        )
    }

    val fallbackBundle = fallback.generate(plan, context, analysis)
    return fallbackBundle.copy(
        warnings = listOf(
            "AI output failed the quality gate and was replaced with heuristic generation: $summary",
        ) + generatedBundle.warnings + fallbackBundle.warnings,
    )
}

private fun normalizeAssertionStyle(content: String, styleGuide: StyleGuide): String {
    return if (styleGuide.assertionStyle == "junit4") {
        content
            .replace("import kotlin.test.Test", "import org.junit.Test")
            .replace("import kotlin.test.assertEquals", "import org.junit.Assert.assertEquals")
            .replace("import kotlin.test.assertTrue", "import org.junit.Assert.assertTrue")
            .replace("import kotlin.test.assertFalse", "import org.junit.Assert.assertFalse")
            .replace("import kotlin.test.assertNull", "import org.junit.Assert.assertNull")
            .replace("import kotlin.test.assertNotNull", "import org.junit.Assert.assertNotNull")
            .replace("import org.junit.jupiter.api.Test", "import org.junit.Test")
    } else {
        content
            .replace("import org.junit.Test", "import kotlin.test.Test")
            .replace("import org.junit.jupiter.api.Test", "import kotlin.test.Test")
    }
}

internal fun sanitizeGeneratedJson(content: String): String {
    val trimmed = content.trim()
    return if (trimmed.startsWith("```")) {
        trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    } else {
        trimmed
    }
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

private fun resolveTypeFile(moduleRoot: Path, typeName: String): Path? {
    val sourceRoots = listOf(
        moduleRoot.resolve("src/main/kotlin"),
        moduleRoot.resolve("src/main/java"),
    ).filter(Files::exists)

    sourceRoots.forEach { sourceRoot ->
        Files.walk(sourceRoot).use { paths ->
            val match = paths
                .filter(Files::isRegularFile)
                .filter { it.fileName.toString() == "$typeName.kt" }
                .findFirst()
            if (match.isPresent) {
                return match.get()
            }
        }
    }

    return null
}

private fun firstDefinedValue(
    environment: Map<String, String>,
    vararg keys: String,
): String? {
    return keys.firstNotNullOfOrNull { key ->
        environment[key]?.trim()?.ifBlank { null }
    }
}

private fun normalizeImports(content: String): String {
    val lines = content.lines()
    val seenImports = linkedSetOf<String>()
    val normalizedLines = buildList(lines.size) {
        lines.forEach { line ->
            if (line.startsWith("import ")) {
                if (seenImports.add(line)) {
                    add(line)
                }
            } else {
                add(line)
            }
        }
    }
    return normalizedLines.joinToString("\n")
}

internal fun stabilizeCoroutineTestPatterns(content: String): String {
    if ("runTest" !in content) {
        return content
    }

    var updated = content
    updated = updated.replace(
        RUN_TEST_TEST_SCHEDULER_CONTEXT_PATTERN,
        "runTest {",
    )
    CLASS_LEVEL_TEST_DISPATCHER_PATTERN.find(updated)?.let { match ->
        val dispatcherName = match.groupValues[1]
        updated = updated.replace(match.value, "")
        updated = updated.replace(
            Regex("""\b${Regex.escape(dispatcherName)}\b"""),
            "StandardTestDispatcher(testScheduler)",
        )
    }

    val rebuilt = StringBuilder()
    var lastIndex = 0
    GENERATED_TEST_BLOCK_PATTERN.findAll(updated).forEach { match ->
        rebuilt.append(updated.substring(lastIndex, match.range.first))
        rebuilt.append(stabilizeCoroutineTestBlock(match.value))
        lastIndex = match.range.last + 1
    }
    rebuilt.append(updated.substring(lastIndex))

    return rebuilt.toString()
}

private fun stabilizeCoroutineTestBlock(block: String): String {
    if ("runTest" !in block || "advanceUntilIdle(" in block) {
        return block
    }

    val lines = block.lines().toMutableList()
    val stateReadIndex = lines.indexOfFirst { line ->
        "viewModel.uiState.value" in line ||
            STATE_READ_PATTERN.containsMatchIn(line)
    }
    if (stateReadIndex <= 0) {
        return block
    }

    val callIndex = (stateReadIndex - 1 downTo 0).firstOrNull { index ->
        VIEW_MODEL_CALL_PATTERN.matches(lines[index].trim())
    } ?: return block

    val indent = lines[callIndex].takeWhile { it == ' ' || it == '\t' }
    lines.add(callIndex + 1, "${indent}advanceUntilIdle()")
    return lines.joinToString("\n")
}

private fun ensureGeneratedImports(content: String): String {
    val imports = buildList {
        if ("viewModelScope" in content && "import androidx.lifecycle.viewModelScope" !in content) {
            add("import androidx.lifecycle.viewModelScope")
        }
        if (
            ("advanceUntilIdle(" in content || "StandardTestDispatcher(" in content) &&
            "import kotlinx.coroutines.ExperimentalCoroutinesApi" !in content
        ) {
            add("import kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
        if (LAUNCH_PATTERN.containsMatchIn(content) && "import kotlinx.coroutines.launch" !in content) {
            add("import kotlinx.coroutines.launch")
        }
        if (MUTABLE_STATE_FLOW_PATTERN.containsMatchIn(content) && "import kotlinx.coroutines.flow.MutableStateFlow" !in content) {
            add("import kotlinx.coroutines.flow.MutableStateFlow")
        }
        if (STATE_FLOW_PATTERN.containsMatchIn(content) && "import kotlinx.coroutines.flow.StateFlow" !in content) {
            add("import kotlinx.coroutines.flow.StateFlow")
        }
        if (MUTABLE_SHARED_FLOW_PATTERN.containsMatchIn(content) && "import kotlinx.coroutines.flow.MutableSharedFlow" !in content) {
            add("import kotlinx.coroutines.flow.MutableSharedFlow")
        }
        if (SHARED_FLOW_PATTERN.containsMatchIn(content) && "import kotlinx.coroutines.flow.SharedFlow" !in content) {
            add("import kotlinx.coroutines.flow.SharedFlow")
        }
        if ("asStateFlow(" in content && "import kotlinx.coroutines.flow.asStateFlow" !in content) {
            add("import kotlinx.coroutines.flow.asStateFlow")
        }
        if ("asSharedFlow(" in content && "import kotlinx.coroutines.flow.asSharedFlow" !in content) {
            add("import kotlinx.coroutines.flow.asSharedFlow")
        }
        if (UPDATE_PATTERN.containsMatchIn(content) && "import kotlinx.coroutines.flow.update" !in content) {
            add("import kotlinx.coroutines.flow.update")
        }
        if (FIRST_PATTERN.containsMatchIn(content) && "import kotlinx.coroutines.flow.first" !in content) {
            add("import kotlinx.coroutines.flow.first")
        }
        if ("runTest(" in content && "import kotlinx.coroutines.test.runTest" !in content) {
            add("import kotlinx.coroutines.test.runTest")
        }
        if (
            "StandardTestDispatcher(" in content &&
            "import kotlinx.coroutines.test.StandardTestDispatcher" !in content
        ) {
            add("import kotlinx.coroutines.test.StandardTestDispatcher")
        }
        if ("advanceUntilIdle(" in content && "import kotlinx.coroutines.test.advanceUntilIdle" !in content) {
            add("import kotlinx.coroutines.test.advanceUntilIdle")
        }
    }
    if (imports.isEmpty()) {
        return content
    }

    val packageLineEnd = if (content.startsWith("package ")) {
        content.indexOf('\n').takeIf { it >= 0 } ?: return content
    } else {
        -1
    }
    val importSectionStart = if (packageLineEnd >= 0) packageLineEnd + 1 else 0
    val insertion = buildString {
        if (packageLineEnd >= 0) {
            append('\n')
        }
        imports.forEach { appendLine(it) }
    }

    return if (importSectionStart == 0) {
        insertion + content
    } else {
        content.substring(0, importSectionStart) + insertion + content.substring(importSectionStart)
    }
}

private fun ensureExperimentalCoroutinesOptIn(content: String): String {
    if (
        ("advanceUntilIdle(" !in content && "StandardTestDispatcher(" !in content) ||
        "@OptIn(ExperimentalCoroutinesApi::class)" in content
    ) {
        return content
    }

    return content.replaceFirst(
        CLASS_DECLARATION_PATTERN,
        "@OptIn(ExperimentalCoroutinesApi::class)\n$0",
    )
}

private val CLASS_LEVEL_TEST_DISPATCHER_PATTERN = Regex(
    """(?m)^\s*private\s+val\s+(\w+)\s*=\s*StandardTestDispatcher\(\)\s*\n?""",
)
private val RUN_TEST_TEST_SCHEDULER_CONTEXT_PATTERN = Regex(
    """runTest\s*\(\s*(?:context\s*=\s*)?StandardTestDispatcher\s*\(\s*(?:this\.)?testScheduler\s*\)\s*\)\s*\{""",
)
private val CLASS_DECLARATION_PATTERN = Regex("""(?m)^class\s+\w+""")
private val GENERATED_TEST_BLOCK_PATTERN = Regex("""@Test[\s\S]*?(?=\n\s*@Test|\n})""")
private val VIEW_MODEL_CALL_PATTERN = Regex("""viewModel\.\w+\(.*\)""")
private val STATE_READ_PATTERN = Regex("""viewModel\.\w+\.value""")
private val LAUNCH_PATTERN = Regex("""(?m)(^|\s)launch\s*\{|\b\w+\.launch\s*\{""")
private val MUTABLE_STATE_FLOW_PATTERN = Regex("""\bMutableStateFlow\b(?:\s*<[^>]+>)?\s*\(""")
private val STATE_FLOW_PATTERN = Regex("""\bStateFlow\s*<|\bStateFlow\b""")
private val MUTABLE_SHARED_FLOW_PATTERN = Regex("""\bMutableSharedFlow\b(?:\s*<[^>]+>)?\s*\(""")
private val SHARED_FLOW_PATTERN = Regex("""\bSharedFlow\s*<|\bSharedFlow\b""")
private val UPDATE_PATTERN = Regex("""\.\s*update\s*\{|(?m)^\s*update\s*\{""")
private val FIRST_PATTERN = Regex("""\.\s*first\s*\(""")
