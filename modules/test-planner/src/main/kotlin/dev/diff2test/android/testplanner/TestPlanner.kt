package dev.diff2test.android.testplanner

import dev.diff2test.android.core.AssertionSpec
import dev.diff2test.android.core.FakeSpec
import dev.diff2test.android.core.RiskLevel
import dev.diff2test.android.core.TestContext
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.core.TestScenario
import dev.diff2test.android.core.TestType
import dev.diff2test.android.core.ViewModelAnalysis

interface TestPlanner {
    fun plan(analysis: ViewModelAnalysis, context: TestContext, testType: TestType): TestPlan
}

class DefaultTestPlanner : TestPlanner {
    override fun plan(analysis: ViewModelAnalysis, context: TestContext, testType: TestType): TestPlan {
        val scenarios = buildList {
            if (analysis.stateHolders.isNotEmpty()) {
                add(
                    TestScenario(
                        name = "initial state is stable",
                        goal = "Verify the default observable state is well-defined.",
                        expectedOutcome = "Initial state can be asserted without Android runtime.",
                        tags = setOf("initial-state"),
                    ),
                )
            }

            analysis.publicMethods.forEach { method ->
                add(
                    TestScenario(
                        name = "${method.name} updates state on success",
                        goal = "Cover the happy path for ${method.signature}.",
                        expectedOutcome = "Observable state or event reflects a successful result.",
                        tags = setOf("success"),
                    ),
                )

                if (method.mutatesState) {
                    add(
                        TestScenario(
                            name = "${method.name} exposes failure state",
                            goal = "Verify negative path behavior for ${method.signature}.",
                            expectedOutcome = "Observable state or event exposes an error outcome.",
                            tags = setOf("failure"),
                        ),
                    )
                }
            }

            if (isEmpty()) {
                add(
                    TestScenario(
                        name = "smoke test public contract",
                        goal = "Fallback scenario for incomplete analysis.",
                        expectedOutcome = "Generated test at least captures the visible contract.",
                        tags = setOf("fallback"),
                    ),
                )
            }
        }

        val fakes = analysis.constructorDependencies.map { dependency ->
            FakeSpec(
                typeName = dependency.type,
                strategy = "fake-or-mock",
                rationale = "Constructor injection keeps ViewModel tests inside local JVM scope.",
            )
        }

        val assertions = listOf(
            AssertionSpec(
                subject = analysis.stateHolders.firstOrNull() ?: "observable outcome",
                assertion = "matches expected value",
                expected = "scenario-specific observable state",
            ),
        )

        val riskLevel = when {
            testType == TestType.INSTRUMENTED -> RiskLevel.HIGH
            analysis.androidFrameworkTouchpoints.isNotEmpty() -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        return TestPlan(
            targetClass = analysis.className,
            targetMethods = analysis.publicMethods.map { it.name },
            testType = testType,
            scenarios = scenarios,
            requiredFakes = fakes,
            assertions = assertions,
            riskLevel = riskLevel,
            notes = listOf(
                "Generate local unit tests by default for ViewModel business logic.",
                "Keep repair attempts bounded to two passes.",
            ),
        )
    }
}

