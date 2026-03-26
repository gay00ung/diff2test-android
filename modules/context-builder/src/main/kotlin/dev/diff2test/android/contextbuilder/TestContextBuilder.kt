package dev.diff2test.android.contextbuilder

import dev.diff2test.android.core.StyleGuide
import dev.diff2test.android.core.TestContext
import dev.diff2test.android.core.ViewModelAnalysis

interface TestContextBuilder {
    fun build(moduleName: String, analysis: ViewModelAnalysis, styleGuide: StyleGuide = StyleGuide()): TestContext
}

class DefaultTestContextBuilder : TestContextBuilder {
    override fun build(moduleName: String, analysis: ViewModelAnalysis, styleGuide: StyleGuide): TestContext {
        val stylePatterns = buildList {
            when (styleGuide.assertionStyle) {
                "junit4" -> add("Use org.junit.Test and org.junit.Assert assertions to match the module test stack.")
                else -> add("Use kotlin.test.Test and kotlin.test assertions to match the module test stack.")
            }
            when (styleGuide.coroutineEntryPoint) {
                "runTest" -> add("Use runTest for coroutine entry points.")
                else -> add("This module does not declare kotlinx-coroutines-test. Do not use runTest, StandardTestDispatcher, or advanceUntilIdle.")
            }
        }
        return TestContext(
            moduleName = moduleName,
            existingTestPatterns = listOf(
                "Prefer constructor-injected fakes or mocks over Hilt in local unit tests.",
                "Keep one observable assertion per scenario.",
            ) + stylePatterns,
            styleGuide = styleGuide,
            collaboratorTypes = analysis.constructorDependencies.map { it.type },
        )
    }
}
