package dev.diff2test.android.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class D2tConfigTest {
    @Test
    fun `loads openai config from toml file`() {
        val configFile = Files.createTempFile("d2t-config", ".toml")
        Files.writeString(
            configFile,
            """
            [ai]
            enabled = true
            provider = "openai"
            protocol = "responses-compatible"
            api_key_env = "OPENAI_API_KEY"
            model = "gpt-5"
            base_url = "https://api.openai.com/v1"
            reasoning_effort = "high"
            connect_timeout_seconds = 45
            request_timeout_seconds = 240
            """.trimIndent(),
        )

        val result = loadConfig(configFile)
        val loaded = assertIs<ConfigLoadResult.Loaded>(result)

        assertEquals(AiProvider.OPENAI, loaded.config.ai.provider)
        assertEquals(AiProtocol.RESPONSES_COMPATIBLE, loaded.config.ai.protocol)
        assertEquals("OPENAI_API_KEY", loaded.config.ai.apiKeyEnv)
        assertEquals("gpt-5", loaded.config.ai.model)
        assertEquals("https://api.openai.com/v1", loaded.config.ai.baseUrl)
        assertEquals("high", loaded.config.ai.reasoningEffort)
        assertEquals(45L, loaded.config.ai.connectTimeoutSeconds)
        assertEquals(240L, loaded.config.ai.requestTimeoutSeconds)
    }

    @Test
    fun `resolves custom config using only api key env name`() {
        val resolved = resolveAiConfiguration(
            loadResult = ConfigLoadResult.Loaded(
                path = defaultConfigPath(),
                config = D2tConfig(
                    ai = D2tAiConfig(
                        provider = AiProvider.CUSTOM,
                        protocol = AiProtocol.RESPONSES_COMPATIBLE,
                        apiKeyEnv = "LLM_API_KEY",
                        model = "qwen3-coder-next-mlx",
                        baseUrl = "http://127.0.0.1:12345",
                        reasoningEffort = "high",
                        connectTimeoutSeconds = 12L,
                        requestTimeoutSeconds = 300L,
                    ),
                ),
            ),
            environment = mapOf("LLM_API_KEY" to "sk-local"),
        )

        assertEquals(AiProvider.CUSTOM, resolved?.provider)
        assertEquals("LLM_API_KEY", resolved?.apiKeyEnv)
        assertTrue(resolved?.apiKeyPresent == true)
        assertEquals("qwen3-coder-next-mlx", resolved?.model)
        assertEquals("http://127.0.0.1:12345", resolved?.baseUrl)
        assertEquals("high", resolved?.reasoningEffort)
        assertEquals(12L, resolved?.connectTimeoutSeconds)
        assertEquals(300L, resolved?.requestTimeoutSeconds)
        assertTrue(resolved?.supportedByGenerator == true)
    }

    @Test
    fun `resolves custom chat completions config`() {
        val resolved = resolveAiConfiguration(
            loadResult = ConfigLoadResult.Loaded(
                path = defaultConfigPath(),
                config = D2tConfig(
                    ai = D2tAiConfig(
                        provider = AiProvider.CUSTOM,
                        protocol = AiProtocol.CHAT_COMPLETIONS,
                        apiKeyEnv = "LLM_API_KEY",
                        model = "qwen3-coder-next-mlx",
                        baseUrl = "http://127.0.0.1:12345/v1",
                    ),
                ),
            ),
            environment = mapOf("LLM_API_KEY" to "sk-local"),
        )

        assertEquals(AiProvider.CUSTOM, resolved?.provider)
        assertEquals(AiProtocol.CHAT_COMPLETIONS, resolved?.protocol)
        assertEquals("http://127.0.0.1:12345/v1", resolved?.baseUrl)
        assertTrue(resolved?.supportedByGenerator == true)
    }

    @Test
    fun `doctor report explains unsupported anthropic protocol`() {
        val loadResult = ConfigLoadResult.Loaded(
            path = defaultConfigPath(),
            config = D2tConfig(
                ai = D2tAiConfig(
                    provider = AiProvider.ANTHROPIC,
                    protocol = AiProtocol.ANTHROPIC_MESSAGES,
                    apiKeyEnv = "ANTHROPIC_API_KEY",
                    model = "claude-sonnet-4-5",
                    baseUrl = "https://api.anthropic.com/v1",
                ),
            ),
        )

        val resolved = resolveAiConfiguration(loadResult, mapOf("ANTHROPIC_API_KEY" to "sk-local"))
        val report = renderDoctorReport(loadResult, resolved)

        assertContains(report, "Provider: anthropic")
        assertContains(report, "Protocol: anthropic_messages")
        assertContains(report, "Supported now: yes")
    }

    @Test
    fun `defaults anthropic config to native messages protocol`() {
        val configFile = Files.createTempFile("d2t-anthropic-config", ".toml")
        Files.writeString(
            configFile,
            """
            [ai]
            provider = "anthropic"
            """.trimIndent(),
        )
        val loadResult = loadConfig(configFile)
        val resolved = resolveAiConfiguration(loadResult, mapOf("ANTHROPIC_API_KEY" to "sk-ant"))

        assertEquals(AiProvider.ANTHROPIC, resolved?.provider)
        assertEquals(AiProtocol.ANTHROPIC_MESSAGES, resolved?.protocol)
        assertEquals("ANTHROPIC_API_KEY", resolved?.apiKeyEnv)
        assertEquals("claude-sonnet-4-5", resolved?.model)
        assertEquals("https://api.anthropic.com/v1", resolved?.baseUrl)
        assertTrue(resolved?.supportedByGenerator == true)
    }

    @Test
    fun `doctor report supports native gemini protocol`() {
        val loadResult = ConfigLoadResult.Loaded(
            path = defaultConfigPath(),
            config = D2tConfig(
                ai = D2tAiConfig(
                    provider = AiProvider.GEMINI,
                    protocol = AiProtocol.GEMINI_GENERATE_CONTENT,
                    apiKeyEnv = "GEMINI_API_KEY",
                    model = "gemini-2.5-pro",
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                ),
            ),
        )

        val resolved = resolveAiConfiguration(loadResult, mapOf("GEMINI_API_KEY" to "sk-local"))
        val report = renderDoctorReport(loadResult, resolved)

        assertContains(report, "Provider: gemini")
        assertContains(report, "Protocol: gemini_generate_content")
        assertContains(report, "Supported now: yes")
    }

    @Test
    fun `template does not include actual secrets`() {
        val template = defaultConfigTemplate()

        assertContains(template, "api_key_env = \"OPENAI_API_KEY\"")
        assertContains(template, "request_timeout_seconds = 180")
        assertContains(template, "protocol = \"chat-completions\"")
        assertTrue("sk-" !in template)
    }
}
