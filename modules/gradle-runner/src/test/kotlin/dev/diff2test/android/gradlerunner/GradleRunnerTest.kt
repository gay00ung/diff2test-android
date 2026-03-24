package dev.diff2test.android.gradlerunner

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GradleRunnerTest {
    @Test
    fun `finds wrapper in parent directories`() {
        val root = Files.createTempDirectory("d2t-gradle-root")
        Files.createFile(root.resolve("gradlew"))
        val nested = Files.createDirectories(root.resolve("apps/cli/build/tmp"))

        val resolved = findGradleProjectRoot(nested)

        assertEquals(root.toAbsolutePath().normalize(), resolved)
    }
}
