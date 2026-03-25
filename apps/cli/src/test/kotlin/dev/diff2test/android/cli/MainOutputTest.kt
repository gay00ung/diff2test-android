package dev.diff2test.android.cli

import dev.diff2test.android.core.ViewModelAnalysis
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MainOutputTest {
    @Test
    fun `renderAnalysisWarnings returns null when no notes exist`() {
        val analysis = ViewModelAnalysis(
            className = "LoginViewModel",
            filePath = Path.of("/tmp/LoginViewModel.kt"),
            notes = emptyList(),
        )

        assertNull(renderAnalysisWarnings(analysis))
    }

    @Test
    fun `renderAnalysisWarnings lists distinct notes`() {
        val analysis = ViewModelAnalysis(
            className = "LoginViewModel",
            filePath = Path.of("/tmp/LoginViewModel.kt"),
            notes = listOf(
                "Compiler-backed symbol resolution is enabled for same-module Kotlin sources.",
                "Compiler-backed symbol resolution is enabled for same-module Kotlin sources.",
                "External dependency resolution may still fall back when classpath symbols are unavailable.",
            ),
        )

        val rendered = renderAnalysisWarnings(analysis)

        assertContains(rendered.orEmpty(), "Analysis warnings:")
        assertContains(rendered.orEmpty(), "- Compiler-backed symbol resolution is enabled for same-module Kotlin sources.")
        assertContains(rendered.orEmpty(), "- External dependency resolution may still fall back when classpath symbols are unavailable.")
        assertEquals(1, Regex("Compiler-backed symbol resolution is enabled for same-module Kotlin sources\\.").findAll(rendered.orEmpty()).count())
    }

    @Test
    fun `renderHelpText explains one dot zero scope and mcp status`() {
        val help = renderHelpText()

        assertContains(help, "1.0 target: CLI for diff-driven Android ViewModel local unit test generation and verification")
        assertContains(help, "MCP app is experimental and currently prints a catalog only")
        assertContains(help, "Responses-compatible endpoints")
        assertContains(help, "native Anthropic Messages API")
        assertContains(help, "native Gemini GenerateContent API")
        assertContains(help, "generated output must pass the built-in quality gate")
    }
}
