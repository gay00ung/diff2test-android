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
        if ("ExperimentalCoroutinesApi" in updated && "import kotlinx.coroutines.ExperimentalCoroutinesApi" !in updated) {
            add("import kotlinx.coroutines.ExperimentalCoroutinesApi")
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
