package dev.diff2test.android.testrepair

import dev.diff2test.android.core.ExecutionResult
import dev.diff2test.android.core.GeneratedFile
import dev.diff2test.android.core.GeneratedTestBundle
import dev.diff2test.android.core.RepairAttempt
import dev.diff2test.android.core.TestPlan

interface TestRepairer {
    fun repair(plan: TestPlan, bundle: GeneratedTestBundle, failure: ExecutionResult, attemptNumber: Int): RepairAttempt
}

class BoundedRepairer : TestRepairer {
    override fun repair(
        plan: TestPlan,
        bundle: GeneratedTestBundle,
        failure: ExecutionResult,
        attemptNumber: Int,
    ): RepairAttempt {
        if (attemptNumber > 2) {
            return RepairAttempt(
                attemptNumber = attemptNumber,
                updatedFiles = bundle.files,
                summary = "Repair boundary reached. Stop after two attempts and report the failure.",
                applied = false,
            )
        }

        val repairedFiles = bundle.files.map { file ->
            GeneratedFile(
                relativePath = file.relativePath,
                content = repairGeneratedKotlin(file.content, failure.stdout),
            )
        }

        val changed = repairedFiles.filterIndexed { index, file ->
            file.content != bundle.files[index].content
        }

        if (changed.isEmpty()) {
            return RepairAttempt(
                attemptNumber = attemptNumber,
                updatedFiles = bundle.files,
                summary = "No bounded repair rule matched. Only import normalization and coroutine test utility fixes are supported automatically.",
                applied = false,
            )
        }

        return RepairAttempt(
            attemptNumber = attemptNumber,
            updatedFiles = repairedFiles,
            summary = "Applied bounded repair rules for common test import and coroutine utility failures.",
            applied = true,
        )
    }
}

internal fun repairGeneratedKotlin(content: String, failureOutput: String): String {
    var updated = content

    if ("import org.junit.Test" in updated || "import org.junit.jupiter.api.Test" in updated || "Unresolved reference: Test" in failureOutput) {
        updated = updated
            .replace("import org.junit.Test", "import kotlin.test.Test")
            .replace("import org.junit.jupiter.api.Test", "import kotlin.test.Test")
        updated = ensureImports(
            updated,
            imports = listOf("import kotlin.test.Test"),
        )
    }

    val kotlinTestImports = buildList {
        if ("assertEquals(" in updated && "import kotlin.test.assertEquals" !in updated) {
            add("import kotlin.test.assertEquals")
        }
        if ("assertTrue(" in updated && "import kotlin.test.assertTrue" !in updated) {
            add("import kotlin.test.assertTrue")
        }
        if ("assertNull(" in updated && "import kotlin.test.assertNull" !in updated) {
            add("import kotlin.test.assertNull")
        }
        if ("assertNotNull(" in updated && "import kotlin.test.assertNotNull" !in updated) {
            add("import kotlin.test.assertNotNull")
        }
    }
    updated = ensureImports(updated, kotlinTestImports)

    val coroutineImports = buildList {
        if ("viewModelScope" in updated && "import androidx.lifecycle.viewModelScope" !in updated) {
            add("import androidx.lifecycle.viewModelScope")
        }
        if ("import kotlinx.coroutines.runTest" in updated) {
            updated = updated.replace("import kotlinx.coroutines.runTest\n", "")
        }
        if ("this@TestScope" in updated) {
            updated = updated.replace("this@TestScope", "testScheduler")
        }
        updated = ensureExperimentalCoroutinesOptIn(stabilizeCoroutineTestPatterns(updated))
        if ("ExperimentalCoroutinesApi" in updated && "import kotlinx.coroutines.ExperimentalCoroutinesApi" !in updated) {
            add("import kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
        if (LAUNCH_PATTERN.containsMatchIn(updated) && "import kotlinx.coroutines.launch" !in updated) {
            add("import kotlinx.coroutines.launch")
        }
        if (MUTABLE_STATE_FLOW_PATTERN.containsMatchIn(updated) && "import kotlinx.coroutines.flow.MutableStateFlow" !in updated) {
            add("import kotlinx.coroutines.flow.MutableStateFlow")
        }
        if (STATE_FLOW_PATTERN.containsMatchIn(updated) && "import kotlinx.coroutines.flow.StateFlow" !in updated) {
            add("import kotlinx.coroutines.flow.StateFlow")
        }
        if (MUTABLE_SHARED_FLOW_PATTERN.containsMatchIn(updated) && "import kotlinx.coroutines.flow.MutableSharedFlow" !in updated) {
            add("import kotlinx.coroutines.flow.MutableSharedFlow")
        }
        if (SHARED_FLOW_PATTERN.containsMatchIn(updated) && "import kotlinx.coroutines.flow.SharedFlow" !in updated) {
            add("import kotlinx.coroutines.flow.SharedFlow")
        }
        if ("asStateFlow(" in updated && "import kotlinx.coroutines.flow.asStateFlow" !in updated) {
            add("import kotlinx.coroutines.flow.asStateFlow")
        }
        if ("asSharedFlow(" in updated && "import kotlinx.coroutines.flow.asSharedFlow" !in updated) {
            add("import kotlinx.coroutines.flow.asSharedFlow")
        }
        if (UPDATE_PATTERN.containsMatchIn(updated) && "import kotlinx.coroutines.flow.update" !in updated) {
            add("import kotlinx.coroutines.flow.update")
        }
        if (FIRST_PATTERN.containsMatchIn(updated) && "import kotlinx.coroutines.flow.first" !in updated) {
            add("import kotlinx.coroutines.flow.first")
        }
        if ("StandardTestDispatcher(" in updated && "import kotlinx.coroutines.test.StandardTestDispatcher" !in updated) {
            add("import kotlinx.coroutines.test.StandardTestDispatcher")
        }
        if (RUN_TEST_PATTERN.containsMatchIn(updated) && "import kotlinx.coroutines.test.runTest" !in updated) {
            add("import kotlinx.coroutines.test.runTest")
        }
        if ("advanceUntilIdle(" in updated && "import kotlinx.coroutines.test.advanceUntilIdle" !in updated) {
            add("import kotlinx.coroutines.test.advanceUntilIdle")
        }
    }

    return ensureImports(updated, coroutineImports)
}

private val RUN_TEST_PATTERN = Regex("""\brunTest\b""")
private val CLASS_LEVEL_TEST_DISPATCHER_PATTERN = Regex(
    """(?m)^\s*private\s+val\s+(\w+)\s*=\s*StandardTestDispatcher\(\)\s*\n?""",
)
private val CLASS_DECLARATION_PATTERN = Regex("""(?m)^class\s+\w+""")
private val GENERATED_TEST_BLOCK_PATTERN = Regex("""@Test[\s\S]*?(?=\n\s*@Test|\n})""")
private val VIEW_MODEL_CALL_PATTERN = Regex("""viewModel\.\w+\(.*\)""")
private val STATE_READ_PATTERN = Regex("""viewModel\.\w+\.value""")
private val LAUNCH_PATTERN = Regex("""(?m)(^|\s)launch\s*\{|\b\w+\.launch\s*\{""")
private val MUTABLE_STATE_FLOW_PATTERN = Regex("""\bMutableStateFlow\b(?:\s*<[^>]+>)?\s*\(""")
private val STATE_FLOW_PATTERN = Regex("""\bStateFlow\s*<|\bStateFlow\b""")
private val MUTABLE_SHARED_FLOW_PATTERN = Regex("""\bMutableSharedFlow\b(?:\s*<[^>]+>)?\s*\(""")
private val SHARED_FLOW_PATTERN = Regex("""\bSharedFlow\s*<|\bSharedFlow\b""")
private val UPDATE_PATTERN = Regex("""\.\s*update\s*\{|(?m)^\s*update\s*\{""")
private val FIRST_PATTERN = Regex("""\.\s*first\s*\(""")

private fun stabilizeCoroutineTestPatterns(content: String): String {
    if ("runTest" !in content) {
        return content
    }

    var updated = content
    CLASS_LEVEL_TEST_DISPATCHER_PATTERN.find(updated)?.let { match ->
        val dispatcherName = match.groupValues[1]
        updated = updated.replace(match.value, "")
        updated = updated.replace(
            Regex("""\b${Regex.escape(dispatcherName)}\b"""),
            "StandardTestDispatcher(testScheduler)",
        )
    }

    val rebuilt = StringBuilder()
    var lastIndex = 0
    GENERATED_TEST_BLOCK_PATTERN.findAll(updated).forEach { match ->
        rebuilt.append(updated.substring(lastIndex, match.range.first))
        rebuilt.append(stabilizeCoroutineTestBlock(match.value))
        lastIndex = match.range.last + 1
    }
    rebuilt.append(updated.substring(lastIndex))

    return rebuilt.toString()
}

private fun stabilizeCoroutineTestBlock(block: String): String {
    if ("runTest" !in block || "advanceUntilIdle(" in block) {
        return block
    }

    val lines = block.lines().toMutableList()
    val stateReadIndex = lines.indexOfFirst { line ->
        "viewModel.uiState.value" in line ||
            STATE_READ_PATTERN.containsMatchIn(line)
    }
    if (stateReadIndex <= 0) {
        return block
    }

    val callIndex = (stateReadIndex - 1 downTo 0).firstOrNull { index ->
        VIEW_MODEL_CALL_PATTERN.matches(lines[index].trim())
    } ?: return block

    val indent = lines[callIndex].takeWhile { it == ' ' || it == '\t' }
    lines.add(callIndex + 1, "${indent}advanceUntilIdle()")
    return lines.joinToString("\n")
}

private fun ensureExperimentalCoroutinesOptIn(content: String): String {
    if (
        ("advanceUntilIdle(" !in content && "StandardTestDispatcher(" !in content) ||
        "@OptIn(ExperimentalCoroutinesApi::class)" in content
    ) {
        return content
    }

    return content.replaceFirst(
        CLASS_DECLARATION_PATTERN,
        "@OptIn(ExperimentalCoroutinesApi::class)\n$0",
    )
}

private fun ensureImports(content: String, imports: List<String>): String {
    if (imports.isEmpty()) {
        return content
    }

    val normalizedImports = imports.distinct().filterNot { it in content }
    if (normalizedImports.isEmpty()) {
        return content
    }

    val packageLineEnd = if (content.startsWith("package ")) {
        content.indexOf('\n').takeIf { it >= 0 } ?: return content
    } else {
        -1
    }
    val importSectionStart = if (packageLineEnd >= 0) packageLineEnd + 1 else 0

    val existingImportLines = content.lineSequence()
        .filter { it.startsWith("import ") }
        .toSet()
    val importsToInsert = normalizedImports.filterNot(existingImportLines::contains)
    if (importsToInsert.isEmpty()) {
        return content
    }

    val insertion = buildString {
        if (packageLineEnd >= 0) {
            append('\n')
        }
        importsToInsert.forEach { appendLine(it) }
    }

    return if (importSectionStart == 0) {
        insertion + content
    } else {
        content.substring(0, importSectionStart) + insertion + content.substring(importSectionStart)
    }
}
