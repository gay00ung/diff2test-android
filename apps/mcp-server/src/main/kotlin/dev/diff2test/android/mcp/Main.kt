package dev.diff2test.android.mcp

data class McpToolDescriptor(
    val name: String,
    val description: String,
)

data class McpResourceDescriptor(
    val uri: String,
    val description: String,
)

data class McpPromptDescriptor(
    val name: String,
    val description: String,
)

object Diff2TestCatalog {
    val tools = listOf(
        McpToolDescriptor("scan_changes", "Detect changed files, hunks, and candidate symbols."),
        McpToolDescriptor("analyze_viewmodel", "Extract ViewModel-centric analysis from Kotlin sources."),
        McpToolDescriptor("collect_test_context", "Load style rules, existing tests, and collaborators."),
        McpToolDescriptor("plan_tests", "Generate a TestPlan from deterministic analysis."),
        McpToolDescriptor("generate_tests", "Render candidate test files from a TestPlan."),
        McpToolDescriptor("run_gradle_tests", "Execute targeted Gradle test tasks."),
        McpToolDescriptor("repair_failed_tests", "Apply bounded repair based on failure logs."),
        McpToolDescriptor("summarize_test_result", "Return a concise execution summary for the client."),
    )

    val resources = listOf(
        McpResourceDescriptor("repo://module-map", "Static map of app and module boundaries."),
        McpResourceDescriptor("repo://test-style-guide", "Project-wide testing conventions."),
        McpResourceDescriptor("repo://existing-test-patterns", "Representative tests for style matching."),
        McpResourceDescriptor("repo://android-test-policy", "Rules for local unit vs instrumented tests."),
        McpResourceDescriptor("repo://recent-changes", "Latest change summary visible to the MCP client."),
    )

    val prompts = listOf(
        McpPromptDescriptor("plan-viewmodel-unit-tests", "Create scenario-first plans for ViewModel tests."),
        McpPromptDescriptor("generate-coroutine-viewmodel-tests", "Render coroutine-aware unit test code."),
        McpPromptDescriptor("repair-gradle-test-failures", "Repair generated tests without changing intent."),
        McpPromptDescriptor("classify-test-target", "Choose src/test or src/androidTest placement."),
    )
}

fun main() {
    println("diff2test-android MCP catalog")
    println()
    println("Tools:")
    Diff2TestCatalog.tools.forEach { tool ->
        println("- ${tool.name}: ${tool.description}")
    }
    println()
    println("Resources:")
    Diff2TestCatalog.resources.forEach { resource ->
        println("- ${resource.uri}: ${resource.description}")
    }
    println()
    println("Prompts:")
    Diff2TestCatalog.prompts.forEach { prompt ->
        println("- ${prompt.name}: ${prompt.description}")
    }
}

