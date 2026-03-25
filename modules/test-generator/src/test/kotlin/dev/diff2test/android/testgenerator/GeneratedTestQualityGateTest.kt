package dev.diff2test.android.testgenerator

import dev.diff2test.android.core.GeneratedFile
import dev.diff2test.android.core.GeneratedTestBundle
import dev.diff2test.android.core.RiskLevel
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.core.TestType
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneratedTestQualityGateTest {
    private val qualityGate = GeneratedTestQualityGate()

    @Test
    fun `passes generated file with concrete assertions and no placeholders`() {
        val report = qualityGate.evaluate(
            bundle(
                """
                package com.example.auth

                import kotlin.test.Test
                import kotlin.test.assertEquals

                class SignUpViewModelGeneratedTest {
                    @Test
                    fun `initial state is stable`() {
                        assertEquals(1, 1)
                    }
                }
                """.trimIndent(),
            ),
        )

        assertTrue(report.passed)
    }

    @Test
    fun `fails placeholder and todo based output`() {
        val report = qualityGate.evaluate(
            bundle(
                """
                package com.example.auth

                import kotlin.test.Test
                import kotlin.test.assertTrue

                class SignUpViewModelGeneratedTest {
                    @Test
                    fun `placeholder`() {
                        // Replace this placeholder with project-specific setup and assertions.
                        assertTrue(true, "placeholder")
                        TODO("implement")
                    }
                }
                """.trimIndent(),
                warnings = listOf("No concrete test heuristics matched. Falling back to placeholder generation."),
            ),
        )

        assertFalse(report.passed)
        assertContains(report.issues.joinToString("\n"), "placeholder test body is not allowed")
        assertContains(report.issues.joinToString("\n"), "trivial always-true assertion is not allowed")
        assertContains(report.issues.joinToString("\n"), "unresolved TODO() remains in generated output")
        assertContains(report.issues.joinToString("\n"), "blocking quality warning")
    }

    @Test
    fun `fails test method without assertion`() {
        val report = qualityGate.evaluate(
            bundle(
                """
                package com.example.auth

                import kotlin.test.Test

                class SignUpViewModelGeneratedTest {
                    @Test
                    fun `does not assert`() {
                        val ignored = 1
                    }
                }
                """.trimIndent(),
            ),
        )

        assertFalse(report.passed)
        assertContains(report.issues.single(), "does not contain a meaningful assertion")
    }

    private fun bundle(content: String, warnings: List<String> = emptyList()): GeneratedTestBundle {
        return GeneratedTestBundle(
            plan = TestPlan(
                targetClass = "SignUpViewModel",
                targetMethods = listOf("submitRegistration"),
                testType = TestType.LOCAL_UNIT,
                scenarios = emptyList(),
                requiredFakes = emptyList(),
                assertions = emptyList(),
                riskLevel = RiskLevel.LOW,
            ),
            files = listOf(
                GeneratedFile(
                    relativePath = Path.of("src/test/kotlin/com/example/auth/SignUpViewModelGeneratedTest.kt"),
                    content = content,
                ),
            ),
            warnings = warnings,
        )
    }
}
