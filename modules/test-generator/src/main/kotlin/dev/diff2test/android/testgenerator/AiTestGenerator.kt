package dev.diff2test.android.testgenerator

import dev.diff2test.android.core.GeneratedFile
import dev.diff2test.android.core.GeneratedTestBundle
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
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds))
        .build(),
) : TestGenerator {
    override fun generate(
        plan: TestPlan,
        context: TestContext,
        analysis: ViewModelAnalysis,
    ): GeneratedTestBundle {
        return try {
            val promptSpec = buildPromptSpec(plan, context, analysis)
            val requestBody = buildResponsesRequest(promptSpec.instructions, promptSpec.input)
            val responseBody = executeResponsesRequest(requestBody)
            val payload = extractStructuredPayload(responseBody)

            GeneratedTestBundle(
                plan = plan,
                files = listOf(
                    GeneratedFile(
                        relativePath = generatedTestRelativePath(plan, analysis),
                        content = sanitizeGeneratedKotlin(payload.content),
                    ),
                ),
                warnings = payload.warnings,
            )
        } catch (error: Exception) {
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
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.baseUrl}/responses"))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("Content-Type", "application/json")
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

internal fun buildResponsesRequest(
    config: ResponsesApiConfig,
    instructions: String,
    input: String,
): String {
    val schema = buildJsonObject {
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
                        put("schema", schema)
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
    val dependencySources = loadDependencySources(analysis)
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
        appendLine("If information is missing, keep the code honest with TODO() or focused fake implementations instead of inventing APIs.")
        appendLine("When dependency source is provided, preserve its method names, parameter lists, and exact return types in all stubs and mocks.")
        appendLine("Use `kotlin.test.Test` and `kotlin.test` assertions. Do not use `org.junit.Test`.")
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
        appendLine("- prefer `runTest` and `StandardTestDispatcher`")
        appendLine("- assert observable state or event outcomes")
        appendLine("- include at least one negative-path test if the source shows validation or failure handling")
        appendLine("- do not emit placeholder `assertTrue(true)` tests")
        appendLine("- do not emit markdown fences in `content`")
    }

    return AiPromptSpec(
        instructions = instructions.trim(),
        input = input.trim(),
    )
}

private fun loadDependencySources(
    analysis: ViewModelAnalysis,
): List<Pair<String, String>> {
    val moduleRoot = inferModuleRootFromTarget(analysis.filePath)
    return analysis.constructorDependencies.mapNotNull { dependency ->
        val sourceFile = resolveTypeFile(moduleRoot, dependency.type) ?: return@mapNotNull null
        dependency.type to Files.readString(sourceFile)
    }
}

internal fun extractStructuredPayload(responseBody: String): StructuredTestPayload {
    val root = Json.parseToJsonElement(responseBody).jsonObject
    val text = root["output_text"]?.jsonPrimitive?.contentOrNull
        ?: root["output"]?.jsonArray?.firstNotNullOfOrNull(::extractOutputText)
        ?: error("OpenAI response did not include output text.")

    val payload = Json.parseToJsonElement(text).jsonObject
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

internal fun sanitizeGeneratedKotlin(content: String): String {
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
    return sanitized
        .replace("import org.junit.Test", "import kotlin.test.Test")
        .replace("import org.junit.jupiter.api.Test", "import kotlin.test.Test")
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
