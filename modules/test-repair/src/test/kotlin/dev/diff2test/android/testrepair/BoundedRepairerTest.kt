package dev.diff2test.android.testrepair

import dev.diff2test.android.core.ExecutionResult
import dev.diff2test.android.core.ExecutionStatus
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

class BoundedRepairerTest {
    @Test
    fun `normalizes junit imports and missing coroutine test imports`() {
        val bundle = GeneratedTestBundle(
            plan = plan(),
            files = listOf(
                GeneratedFile(
                    relativePath = Path.of("src/test/kotlin/com/example/auth/LoginViewModelGeneratedTest.kt"),
                    content = """
                        package com.example.auth

                        import org.junit.Test

                        class LoginViewModelGeneratedTest {
                            @Test
                            fun `runs`() = runTest {
                                val dispatcher = StandardTestDispatcher()
                                advanceUntilIdle()
                                assertEquals(1, 1)
                            }
                        }
                    """.trimIndent(),
                ),
            ),
        )

        val repair = BoundedRepairer().repair(
            plan = plan(),
            bundle = bundle,
            failure = ExecutionResult(
                status = ExecutionStatus.FAILED,
                command = listOf("./gradlew", ":app:test"),
                exitCode = 1,
                stdout = "Unresolved reference: Test\nUnresolved reference: runTest\nUnresolved reference: StandardTestDispatcher",
            ),
            attemptNumber = 1,
        )

        assertTrue(repair.applied)
        val content = repair.updatedFiles.single().content
        assertContains(content, "import kotlin.test.Test")
        assertContains(content, "import kotlin.test.assertEquals")
        assertContains(content, "import kotlinx.coroutines.test.runTest")
        assertContains(content, "import kotlinx.coroutines.test.StandardTestDispatcher")
        assertContains(content, "import kotlinx.coroutines.test.advanceUntilIdle")
    }

    @Test
    fun `stops when no bounded repair rule matches`() {
        val bundle = GeneratedTestBundle(
            plan = plan(),
            files = listOf(
                GeneratedFile(
                    relativePath = Path.of("src/test/kotlin/com/example/auth/LoginViewModelGeneratedTest.kt"),
                    content = "package com.example.auth\n\nclass LoginViewModelGeneratedTest",
                ),
            ),
        )

        val repair = BoundedRepairer().repair(
            plan = plan(),
            bundle = bundle,
            failure = ExecutionResult(
                status = ExecutionStatus.FAILED,
                command = listOf("./gradlew", ":app:test"),
                exitCode = 1,
                stdout = "Type mismatch",
            ),
            attemptNumber = 1,
        )

        assertFalse(repair.applied)
        assertContains(repair.summary, "No bounded repair rule matched")
    }

    private fun plan() = TestPlan(
        targetClass = "LoginViewModel",
        targetMethods = listOf("login"),
        testType = TestType.LOCAL_UNIT,
        scenarios = emptyList(),
        requiredFakes = emptyList(),
        assertions = emptyList(),
        riskLevel = RiskLevel.LOW,
    )
}
