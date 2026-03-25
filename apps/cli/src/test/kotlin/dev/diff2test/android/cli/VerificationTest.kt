package dev.diff2test.android.cli

import dev.diff2test.android.core.RiskLevel
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.core.TestType
import dev.diff2test.android.core.ViewModelAnalysis
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VerificationTest {
    @Test
    fun `infers generated test target for nested module`() {
        val repoRoot = Files.createTempDirectory("d2t-verify-root")
        val buildRoot = Files.createDirectories(repoRoot.resolve("fixtures/sample-app"))
        Files.writeString(buildRoot.resolve("settings.gradle.kts"), "rootProject.name = \"sample-app\"\n")
        val sourcePath = Files.createDirectories(
            buildRoot.resolve("app/src/main/java/com/example/auth"),
        ).resolve("SignUpViewModel.kt")
        Files.writeString(sourcePath, "package com.example.auth\n")

        val analysis = ViewModelAnalysis(
            className = "SignUpViewModel",
            packageName = "com.example.auth",
            filePath = sourcePath,
        )
        val plan = TestPlan(
            targetClass = "SignUpViewModel",
            targetMethods = listOf("submitRegistration"),
            testType = TestType.LOCAL_UNIT,
            scenarios = emptyList(),
            requiredFakes = emptyList(),
            assertions = emptyList(),
            riskLevel = RiskLevel.LOW,
        )

        val target = inferGeneratedTestTarget(analysis, plan)

        assertEquals(buildRoot.resolve("app"), target.moduleRoot)
        assertEquals(
            buildRoot.resolve("app/src/test/kotlin/com/example/auth/SignUpViewModelGeneratedTest.kt"),
            target.filePath,
        )
        assertEquals("com.example.auth.SignUpViewModelGeneratedTest", target.testFilter)
        assertEquals(":app:test", target.gradleTask)
    }

    @Test
    fun `requires generated test file before verify`() {
        val root = Files.createTempDirectory("d2t-verify-target")
        val target = GeneratedTestTarget(
            moduleRoot = root,
            filePath = root.resolve("src/test/kotlin/com/example/MissingGeneratedTest.kt"),
            testFilter = "com.example.MissingGeneratedTest",
            gradleTask = ":app:test",
        )

        val error = assertFailsWith<IllegalStateException> {
            requireGeneratedTestFile(target)
        }

        assertEquals(
            "Generated test file not found: ${target.filePath}. Run `generate --write` or `auto` first.",
            error.message,
        )
    }
}
