package dev.diff2test.android.testclassifier

import dev.diff2test.android.core.TestContext
import dev.diff2test.android.core.TestType
import dev.diff2test.android.core.ViewModelAnalysis

interface TestClassifier {
    fun classify(analysis: ViewModelAnalysis, context: TestContext): TestType
}

class DefaultTestClassifier : TestClassifier {
    override fun classify(analysis: ViewModelAnalysis, context: TestContext): TestType {
        val needsAndroidRuntime = analysis.androidFrameworkTouchpoints.any { touchpoint ->
            touchpoint.startsWith("android.") ||
                "Context" in touchpoint ||
                "NavController" in touchpoint ||
                "Fragment" in touchpoint ||
                "Activity" in touchpoint
        }

        return if (needsAndroidRuntime) {
            TestType.INSTRUMENTED
        } else {
            TestType.LOCAL_UNIT
        }
    }
}

