package dev.diff2test.android.styleindex

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultStyleIndexerTest {
    @Test
    fun `detects junit4 and missing coroutine test support from android module`() {
        val projectRoot = Files.createTempDirectory("style-index")
        Files.writeString(projectRoot.resolve("settings.gradle.kts"), """rootProject.name = "sample"""")
        val moduleRoot = Files.createDirectories(projectRoot.resolve("app"))
        Files.createDirectories(moduleRoot.resolve("src/test/java/com/example"))
        Files.writeString(
            moduleRoot.resolve("build.gradle.kts"),
            """
                plugins {
                    id("com.android.application")
                }

                dependencies {
                    testImplementation(libs.junit)
                }
            """.trimIndent(),
        )
        Files.writeString(
            moduleRoot.resolve("src/test/java/com/example/ExampleUnitTest.kt"),
            """
                package com.example

                import org.junit.Test
                import org.junit.Assert.assertEquals
            """.trimIndent(),
        )
        Files.createDirectories(projectRoot.resolve("gradle"))
        Files.writeString(
            projectRoot.resolve("gradle/libs.versions.toml"),
            """
                [libraries]
                junit = { group = "junit", name = "junit", version = "4.13.2" }
            """.trimIndent(),
        )

        val styleGuide = DefaultStyleIndexer().index(moduleRoot)

        assertEquals("junit4", styleGuide.assertionStyle)
        assertEquals("unavailable", styleGuide.coroutineEntryPoint)
    }

    @Test
    fun `ignores previously generated tests when inferring module style`() {
        val projectRoot = Files.createTempDirectory("style-index-generated")
        Files.writeString(projectRoot.resolve("settings.gradle.kts"), """rootProject.name = "sample"""")
        val moduleRoot = Files.createDirectories(projectRoot.resolve("app"))
        Files.createDirectories(moduleRoot.resolve("src/test/kotlin/com/example"))
        Files.writeString(
            moduleRoot.resolve("build.gradle.kts"),
            """
                plugins {
                    id("com.android.application")
                }

                dependencies {
                    testImplementation(libs.junit)
                }
            """.trimIndent(),
        )
        Files.writeString(
            moduleRoot.resolve("src/test/kotlin/com/example/LegacyGeneratedTest.kt"),
            """
                package com.example

                import kotlinx.coroutines.test.runTest
                import kotlinx.coroutines.test.StandardTestDispatcher

                class LegacyGeneratedTest
            """.trimIndent(),
        )
        Files.createDirectories(projectRoot.resolve("gradle"))
        Files.writeString(
            projectRoot.resolve("gradle/libs.versions.toml"),
            """
                [libraries]
                junit = { group = "junit", name = "junit", version = "4.13.2" }
            """.trimIndent(),
        )

        val styleGuide = DefaultStyleIndexer().index(moduleRoot)

        assertEquals("junit4", styleGuide.assertionStyle)
        assertEquals("unavailable", styleGuide.coroutineEntryPoint)
    }
}
