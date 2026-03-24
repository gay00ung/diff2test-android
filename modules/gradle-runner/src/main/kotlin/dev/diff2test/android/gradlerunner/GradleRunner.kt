package dev.diff2test.android.gradlerunner

import dev.diff2test.android.core.ExecutionResult
import dev.diff2test.android.core.ExecutionStatus
import dev.diff2test.android.core.GradleRunRequest

interface GradleRunner {
    fun run(request: GradleRunRequest): ExecutionResult
}

class JvmGradleRunner : GradleRunner {
    override fun run(request: GradleRunRequest): ExecutionResult {
        val wrapper = request.workingDirectory.resolve("gradlew").toFile()
        val executable = if (wrapper.exists()) wrapper.absolutePath else "gradle"
        val command = mutableListOf(executable, request.task)

        if (!request.testFilter.isNullOrBlank()) {
            command += listOf("--tests", request.testFilter)
        }

        val process = ProcessBuilder(command)
            .directory(request.workingDirectory.toFile())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        return ExecutionResult(
            status = if (exitCode == 0) ExecutionStatus.PASSED else ExecutionStatus.FAILED,
            command = command,
            exitCode = exitCode,
            stdout = output,
        )
    }
}

