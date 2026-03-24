package dev.diff2test.android.contextbuilder

import dev.diff2test.android.core.StyleGuide
import dev.diff2test.android.core.TestContext
import dev.diff2test.android.core.ViewModelAnalysis

interface TestContextBuilder {
    fun build(moduleName: String, analysis: ViewModelAnalysis, styleGuide: StyleGuide = StyleGuide()): TestContext
}

class DefaultTestContextBuilder : TestContextBuilder {
    override fun build(moduleName: String, analysis: ViewModelAnalysis, styleGuide: StyleGuide): TestContext {
        return TestContext(
            moduleName = moduleName,
            existingTestPatterns = listOf(
                "Prefer constructor-injected fakes or mocks over Hilt in local unit tests.",
                "Use ${styleGuide.coroutineEntryPoint} for coroutine entry points.",
                "Keep one observable assertion per scenario.",
            ),
            styleGuide = styleGuide,
            collaboratorTypes = analysis.constructorDependencies.map { it.type },
        )
    }
}

