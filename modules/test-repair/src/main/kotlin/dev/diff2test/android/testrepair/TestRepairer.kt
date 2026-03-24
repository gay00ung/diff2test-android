package dev.diff2test.android.testrepair

import dev.diff2test.android.core.ExecutionResult
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
        return RepairAttempt(
            attemptNumber = attemptNumber,
            updatedFiles = bundle.files,
            summary = "Repair pass $attemptNumber is not implemented yet. Feed failure logs into an LLM-backed patcher here.",
            applied = false,
        )
    }
}

