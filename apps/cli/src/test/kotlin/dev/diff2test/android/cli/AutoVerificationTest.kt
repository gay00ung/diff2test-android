package dev.diff2test.android.cli

import dev.diff2test.android.core.ExecutionResult
import dev.diff2test.android.core.ExecutionStatus
import dev.diff2test.android.core.GeneratedFile
import dev.diff2test.android.core.GeneratedTestBundle
import dev.diff2test.android.core.RiskLevel
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.core.TestType
import dev.diff2test.android.core.ViewModelAnalysis
import dev.diff2test.android.gradlerunner.GradleRunner
import dev.diff2test.android.testrepair.TestRepairer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoVerificationTest {
    @Test
    fun `verifyGeneratedBundle reruns verification after bounded repair`() {
        val repoRoot = Files.createTempDirectory("d2t-auto-verify")
        Files.writeString(repoRoot.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"\ninclude(\":app\")\n")
        Files.writeString(repoRoot.resolve("gradlew"), "#!/bin/sh\n")

        val sourcePath = repoRoot.resolve("app/src/main/java/com/example/auth/LoginViewModel.kt")
        Files.createDirectories(sourcePath.parent)
        Files.writeString(sourcePath, "package com.example.auth\nclass LoginViewModel\n")

        val generatedFile = repoRoot.resolve("app/src/test/kotlin/com/example/auth/LoginViewModelGeneratedTest.kt")
        Files.createDirectories(generatedFile.parent)
        Files.writeString(
            generatedFile,
            """
                package com.example.auth

                import org.junit.Test

                class LoginViewModelGeneratedTest {
                    @Test
                    fun `runs`() = runTest {
                        assertEquals(1, 1)
                    }
                }
            """.trimIndent(),
        )

        val analysis = ViewModelAnalysis(
            className = "LoginViewModel",
            packageName = "com.example.auth",
            filePath = sourcePath,
        )
        val bundle = GeneratedTestBundle(
            plan = TestPlan(
                targetClass = "LoginViewModel",
                targetMethods = listOf("login"),
                testType = TestType.LOCAL_UNIT,
                scenarios = emptyList(),
                requiredFakes = emptyList(),
                assertions = emptyList(),
                riskLevel = RiskLevel.LOW,
            ),
            files = listOf(
                GeneratedFile(
                    relativePath = Path.of("src/test/kotlin/com/example/auth/LoginViewModelGeneratedTest.kt"),
                    content = Files.readString(generatedFile),
                ),
            ),
        )

        val runner = FakeGradleRunner(
            results = ArrayDeque(
                listOf(
                    ExecutionResult(
                        status = ExecutionStatus.FAILED,
                        command = listOf("./gradlew", ":app:test"),
                        exitCode = 1,
                        stdout = "Unresolved reference: Test\nUnresolved reference: runTest",
                    ),
                    ExecutionResult(
                        status = ExecutionStatus.PASSED,
                        command = listOf("./gradlew", ":app:test"),
                        exitCode = 0,
                        stdout = "BUILD SUCCESSFUL",
                    ),
                ),
            ),
        )

        val verification = verifyGeneratedBundle(
            analysis = analysis,
            bundle = bundle,
            gradleRunner = runner,
            repairer = dev.diff2test.android.testrepair.BoundedRepairer(),
            attemptRepair = true,
        )

        assertEquals(2, runner.invocations)
        assertTrue(verification.repairAttempt?.applied == true)
        assertEquals(ExecutionStatus.PASSED, verification.finalResult.status)
        val repairedContent = Files.readString(generatedFile)
        assertContains(repairedContent, "import kotlin.test.Test")
        assertContains(repairedContent, "import kotlinx.coroutines.test.runTest")
    }

    @Test
    fun `verifyGeneratedBundle does not repair when disabled`() {
        val repoRoot = Files.createTempDirectory("d2t-auto-no-repair")
        Files.writeString(repoRoot.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"\ninclude(\":app\")\n")
        Files.writeString(repoRoot.resolve("gradlew"), "#!/bin/sh\n")

        val sourcePath = repoRoot.resolve("app/src/main/java/com/example/auth/LoginViewModel.kt")
        Files.createDirectories(sourcePath.parent)
        Files.writeString(sourcePath, "package com.example.auth\nclass LoginViewModel\n")

        val generatedFile = repoRoot.resolve("app/src/test/kotlin/com/example/auth/LoginViewModelGeneratedTest.kt")
        Files.createDirectories(generatedFile.parent)
        Files.writeString(generatedFile, "package com.example.auth\nclass LoginViewModelGeneratedTest")

        val bundle = GeneratedTestBundle(
            plan = TestPlan(
                targetClass = "LoginViewModel",
                targetMethods = listOf("login"),
                testType = TestType.LOCAL_UNIT,
                scenarios = emptyList(),
                requiredFakes = emptyList(),
                assertions = emptyList(),
                riskLevel = RiskLevel.LOW,
            ),
            files = listOf(
                GeneratedFile(
                    relativePath = Path.of("src/test/kotlin/com/example/auth/LoginViewModelGeneratedTest.kt"),
                    content = Files.readString(generatedFile),
                ),
            ),
        )
        val runner = FakeGradleRunner(
            results = ArrayDeque(
                listOf(
                    ExecutionResult(
                        status = ExecutionStatus.FAILED,
                        command = listOf("./gradlew", ":app:test"),
                        exitCode = 1,
                        stdout = "Type mismatch",
                    ),
                ),
            ),
        )

        val verification = verifyGeneratedBundle(
            analysis = ViewModelAnalysis(
                className = "LoginViewModel",
                packageName = "com.example.auth",
                filePath = sourcePath,
            ),
            bundle = bundle,
            gradleRunner = runner,
            repairer = NoopRepairer(),
            attemptRepair = false,
        )

        assertEquals(1, runner.invocations)
        assertFalse(verification.repairAttempt?.applied ?: false)
        assertEquals(ExecutionStatus.FAILED, verification.finalResult.status)
    }
}

private class FakeGradleRunner(
    private val results: ArrayDeque<ExecutionResult>,
) : GradleRunner {
    var invocations: Int = 0
        private set

    override fun run(request: dev.diff2test.android.core.GradleRunRequest): ExecutionResult {
        invocations += 1
        return results.removeFirst()
    }
}

private class NoopRepairer : TestRepairer {
    override fun repair(
        plan: TestPlan,
        bundle: GeneratedTestBundle,
        failure: ExecutionResult,
        attemptNumber: Int,
    ) = dev.diff2test.android.core.RepairAttempt(
        attemptNumber = attemptNumber,
        updatedFiles = bundle.files,
        summary = "No repair",
        applied = false,
    )
}
