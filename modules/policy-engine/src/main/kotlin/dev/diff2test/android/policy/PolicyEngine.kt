package dev.diff2test.android.policy

import dev.diff2test.android.core.ExecutionResult
import dev.diff2test.android.core.ExecutionStatus
import dev.diff2test.android.core.PolicyDecision
import dev.diff2test.android.core.TestPlan

interface PolicyEngine {
    fun evaluate(plan: TestPlan, executionResult: ExecutionResult, repairAttempts: Int): PolicyDecision
}

class DefaultPolicyEngine : PolicyEngine {
    override fun evaluate(
        plan: TestPlan,
        executionResult: ExecutionResult,
        repairAttempts: Int,
    ): PolicyDecision {
        return when {
            executionResult.status == ExecutionStatus.PASSED -> PolicyDecision(
                shouldApplyPatch = false,
                shouldRetry = false,
                maxRetries = 2,
                rationale = "Keep patch preview as the default, even after passing tests.",
            )

            repairAttempts >= 2 -> PolicyDecision(
                shouldApplyPatch = false,
                shouldRetry = false,
                maxRetries = 2,
                rationale = "Stop after two repair attempts and report the failure.",
            )

            else -> PolicyDecision(
                shouldApplyPatch = false,
                shouldRetry = true,
                maxRetries = 2,
                rationale = "Allow bounded repair while keeping auto-apply disabled by default.",
            )
        }
    }
}

