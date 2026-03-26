package dev.diff2test.android.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerificationTaskInferenceTest {
    @Test
    fun `uses testDebugUnitTest for Android modules`() {
        val repoRoot = Files.createTempDirectory("d2t-android-module")
        Files.writeString(repoRoot.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"\ninclude(\":app\")\n")
        val moduleRoot = Files.createDirectories(repoRoot.resolve("app"))
        Files.writeString(
            moduleRoot.resolve("build.gradle.kts"),
            """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                }

                android {
                    namespace = "com.example.app"
                }
            """.trimIndent(),
        )

        assertTrue(isAndroidModule(moduleRoot))
        assertEquals(":app:testDebugUnitTest", inferModuleTestTask(moduleRoot))
    }

    @Test
    fun `uses test for plain JVM modules`() {
        val repoRoot = Files.createTempDirectory("d2t-jvm-module")
        Files.writeString(repoRoot.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"\ninclude(\":app\")\n")
        val moduleRoot = Files.createDirectories(repoRoot.resolve("app"))
        Files.writeString(
            moduleRoot.resolve("build.gradle.kts"),
            """
                plugins {
                    kotlin("jvm")
                }
            """.trimIndent(),
        )

        assertFalse(isAndroidModule(moduleRoot))
        assertEquals(":app:test", inferModuleTestTask(moduleRoot))
    }
}
