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
                ),
            ),
        )

        val resolved = resolveAiConfiguration(loadResult, mapOf("ANTHROPIC_API_KEY" to "sk-local"))
        val report = renderDoctorReport(loadResult, resolved)

        assertContains(report, "Provider: anthropic")
        assertContains(report, "Supported now: no")
        assertContains(report, "Use `provider = \"custom\"`")
    }

    @Test
    fun `template does not include actual secrets`() {
        val template = defaultConfigTemplate()

        assertContains(template, "api_key_env = \"OPENAI_API_KEY\"")
        assertTrue("sk-" !in template)
    }
}
