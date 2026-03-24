package dev.diff2test.android.cli

import dev.diff2test.android.testgenerator.ResponsesApiConfig
import java.nio.file.Files
import java.nio.file.Path
import org.tomlj.Toml
import org.tomlj.TomlParseResult

enum class AiProvider {
    OPENAI,
    ANTHROPIC,
    CUSTOM,
    DISABLED,
}

enum class AiProtocol {
    RESPONSES_COMPATIBLE,
    ANTHROPIC_MESSAGES,
}

data class D2tAiConfig(
    val enabled: Boolean = true,
    val provider: AiProvider = AiProvider.OPENAI,
    val protocol: AiProtocol = AiProtocol.RESPONSES_COMPATIBLE,
    val apiKeyEnv: String? = null,
    val model: String? = null,
    val baseUrl: String? = null,
    val reasoningEffort: String? = null,
)

data class D2tConfig(
    val ai: D2tAiConfig = D2tAiConfig(),
)

sealed interface ConfigLoadResult {
    val path: Path

    data class Missing(override val path: Path) : ConfigLoadResult

    data class Invalid(
        override val path: Path,
        val message: String,
    ) : ConfigLoadResult

    data class Loaded(
        override val path: Path,
        val config: D2tConfig,
    ) : ConfigLoadResult
}

data class ResolvedAiConfiguration(
    val source: String,
    val provider: AiProvider,
    val protocol: AiProtocol,
    val apiKeyEnv: String,
    val apiKeyPresent: Boolean,
    val model: String,
    val baseUrl: String?,
    val reasoningEffort: String?,
    val supportedByGenerator: Boolean,
    val issue: String? = null,
)

fun defaultConfigPath(): Path {
    val custom = System.getenv("D2T_CONFIG_PATH")?.trim().orEmpty()
    if (custom.isNotBlank()) {
        return Path.of(custom).toAbsolutePath().normalize()
    }
    return Path.of(System.getProperty("user.home"), ".config", "d2t", "config.toml")
        .toAbsolutePath()
        .normalize()
}

fun loadConfig(configPath: Path = defaultConfigPath()): ConfigLoadResult {
    if (!Files.exists(configPath)) {
        return ConfigLoadResult.Missing(configPath)
    }

    return try {
        val parseResult = Toml.parse(configPath)
        if (parseResult.hasErrors()) {
            val message = parseResult.errors().joinToString("; ") { error ->
                "${error.position()}: ${error.message}"
            }
            ConfigLoadResult.Invalid(configPath, message)
        } else {
            ConfigLoadResult.Loaded(configPath, parseConfig(parseResult))
        }
    } catch (error: Exception) {
        ConfigLoadResult.Invalid(
            path = configPath,
            message = error.message ?: error::class.simpleName.orEmpty(),
        )
    }
}

internal fun parseConfig(parseResult: TomlParseResult): D2tConfig {
    val ai = parseResult.getTable("ai")
    if (ai == null) {
        return D2tConfig()
    }

    val provider = parseProvider(ai.getString("provider"))
    val protocol = parseProtocol(ai.getString("protocol"), provider)

    return D2tConfig(
        ai = D2tAiConfig(
            enabled = ai.getBoolean("enabled") ?: true,
            provider = provider,
            protocol = protocol,
            apiKeyEnv = ai.getString("api_key_env"),
            model = ai.getString("model"),
            baseUrl = ai.getString("base_url"),
            reasoningEffort = ai.getString("reasoning_effort"),
        ),
    )
}

private fun parseProvider(rawValue: String?): AiProvider {
    return when (rawValue?.trim()?.lowercase()) {
        null, "" -> AiProvider.OPENAI
        "openai" -> AiProvider.OPENAI
        "anthropic" -> AiProvider.ANTHROPIC
        "custom" -> AiProvider.CUSTOM
        "disabled" -> AiProvider.DISABLED
        else -> error("Unsupported ai.provider value: $rawValue")
    }
}

private fun parseProtocol(rawValue: String?, provider: AiProvider): AiProtocol {
    return when (rawValue?.trim()?.lowercase()) {
        null, "" -> defaultProtocolFor(provider)
        "responses-compatible", "responses_compatible", "responses" -> AiProtocol.RESPONSES_COMPATIBLE
        "anthropic-messages", "anthropic_messages", "messages" -> AiProtocol.ANTHROPIC_MESSAGES
        else -> error("Unsupported ai.protocol value: $rawValue")
    }
}

private fun defaultProtocolFor(provider: AiProvider): AiProtocol {
    return when (provider) {
        AiProvider.ANTHROPIC -> AiProtocol.ANTHROPIC_MESSAGES
        else -> AiProtocol.RESPONSES_COMPATIBLE
    }
}

private fun defaultApiKeyEnvFor(provider: AiProvider): String? {
    return when (provider) {
        AiProvider.OPENAI -> "OPENAI_API_KEY"
        AiProvider.ANTHROPIC -> "ANTHROPIC_API_KEY"
        AiProvider.CUSTOM, AiProvider.DISABLED -> null
    }
}

private fun defaultBaseUrlFor(provider: AiProvider): String? {
    return when (provider) {
        AiProvider.OPENAI -> "https://api.openai.com/v1"
        AiProvider.ANTHROPIC -> "https://api.anthropic.com/v1"
        AiProvider.CUSTOM, AiProvider.DISABLED -> null
    }
}

internal fun resolveAiConfiguration(
    loadResult: ConfigLoadResult,
    environment: Map<String, String>,
    modelOverride: String? = null,
): ResolvedAiConfiguration? {
    val loaded = loadResult as? ConfigLoadResult.Loaded ?: return null
    val ai = loaded.config.ai

    if (!ai.enabled || ai.provider == AiProvider.DISABLED) {
        return null
    }

    val apiKeyEnv = ai.apiKeyEnv ?: defaultApiKeyEnvFor(ai.provider)
    val apiKeyPresent = apiKeyEnv != null && !environment[apiKeyEnv].isNullOrBlank()
    val model = modelOverride ?: ai.model ?: defaultModelFor(ai.provider)
    val baseUrl = ai.baseUrl ?: defaultBaseUrlFor(ai.provider)
    val issue = when {
        apiKeyEnv == null -> "Set ai.api_key_env in config.toml."
        !apiKeyPresent -> "Environment variable `$apiKeyEnv` is not set."
        model == null -> "Set ai.model in config.toml or pass --model."
        ai.protocol != AiProtocol.RESPONSES_COMPATIBLE ->
            "Provider `${ai.provider.name.lowercase()}` uses `${ai.protocol.name.lowercase()}` which is not implemented yet. Use `provider = \"custom\"` with `protocol = \"responses-compatible\"` for a compatible endpoint."
        baseUrl.isNullOrBlank() -> "Set ai.base_url in config.toml."
        else -> null
    }

    return ResolvedAiConfiguration(
        source = loaded.path.toString(),
        provider = ai.provider,
        protocol = ai.protocol,
        apiKeyEnv = apiKeyEnv ?: "",
        apiKeyPresent = apiKeyPresent,
        model = model ?: "",
        baseUrl = baseUrl,
        reasoningEffort = ai.reasoningEffort,
        supportedByGenerator = issue == null,
        issue = issue,
    )
}

internal fun toResponsesApiConfig(
    resolved: ResolvedAiConfiguration,
    environment: Map<String, String>,
): ResponsesApiConfig? {
    if (!resolved.supportedByGenerator) {
        return null
    }
    val apiKey = environment[resolved.apiKeyEnv]?.trim().orEmpty()
    if (apiKey.isBlank() || resolved.baseUrl.isNullOrBlank()) {
        return null
    }
    return ResponsesApiConfig(
        apiKey = apiKey,
        model = resolved.model,
        baseUrl = resolved.baseUrl,
        reasoningEffort = resolved.reasoningEffort,
    )
}

internal fun defaultModelFor(provider: AiProvider): String? {
    return when (provider) {
        AiProvider.OPENAI -> "gpt-5"
        AiProvider.ANTHROPIC -> "claude-sonnet-4-5"
        AiProvider.CUSTOM, AiProvider.DISABLED -> null
    }
}

fun renderDoctorReport(
    loadResult: ConfigLoadResult,
    resolved: ResolvedAiConfiguration?,
): String {
    return buildString {
        when (loadResult) {
            is ConfigLoadResult.Missing -> {
                appendLine("Config: missing")
                appendLine("Path: ${loadResult.path}")
                appendLine("Run `./d2t init` to create a starter config.")
            }

            is ConfigLoadResult.Invalid -> {
                appendLine("Config: invalid")
                appendLine("Path: ${loadResult.path}")
                appendLine("Error: ${loadResult.message}")
            }

            is ConfigLoadResult.Loaded -> {
                appendLine("Config: loaded")
                appendLine("Path: ${loadResult.path}")
                if (resolved == null) {
                    appendLine("AI: disabled")
                } else {
                    appendLine("Provider: ${resolved.provider.name.lowercase()}")
                    appendLine("Protocol: ${resolved.protocol.name.lowercase()}")
                    appendLine("Model: ${resolved.model}")
                    appendLine("Base URL: ${resolved.baseUrl ?: "(none)"}")
                    appendLine("API key env: ${resolved.apiKeyEnv}")
                    appendLine("API key present: ${if (resolved.apiKeyPresent) "yes" else "no"}")
                    appendLine("Reasoning effort: ${resolved.reasoningEffort ?: "(default)"}")
                    appendLine("Supported now: ${if (resolved.supportedByGenerator) "yes" else "no"}")
                    if (resolved.issue != null) {
                        appendLine("Issue: ${resolved.issue}")
                    }
                }
            }
        }
    }.trimEnd()
}

fun defaultConfigTemplate(): String {
    return """
# diff2test-android user config

[ai]
enabled = true

# `openai` works out of the box with the official OpenAI Responses API.
# `custom` is for self-hosted or gateway endpoints that expose a Responses-compatible API.
# `anthropic` is reserved for a future native adapter; use `custom` if your Anthropic-style gateway is Responses-compatible.
provider = "openai"
protocol = "responses-compatible"

# Store only the environment variable name here, not the secret itself.
api_key_env = "OPENAI_API_KEY"

model = "gpt-5"
base_url = "https://api.openai.com/v1"
# reasoning_effort = "high"

# Example for a local or self-hosted Responses-compatible server:
# provider = "custom"
# protocol = "responses-compatible"
# api_key_env = "LLM_API_KEY"
# model = "qwen3-coder-next-mlx"
# base_url = "http://127.0.0.1:12345"
# reasoning_effort = "high"
    """.trimIndent() + "\n"
}
