package dev.diff2test.android.testplanner

import dev.diff2test.android.core.CollaboratorDependency
import dev.diff2test.android.core.StyleGuide
import dev.diff2test.android.core.TargetMethod
import dev.diff2test.android.core.TestContext
import dev.diff2test.android.core.TestType
import dev.diff2test.android.core.ViewModelAnalysis
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class DefaultTestPlannerTest {
    @Test
    fun `creates failure coverage for state mutating methods`() {
        val analysis = ViewModelAnalysis(
            className = "LoginViewModel",
            filePath = Path.of("LoginViewModel.kt"),
            constructorDependencies = listOf(CollaboratorDependency(name = "repo", type = "LoginRepository")),
            publicMethods = listOf(
                TargetMethod(
                    name = "submit",
                    signature = "suspend fun submit()",
                    isSuspend = true,
                    mutatesState = true,
                ),
            ),
            stateHolders = listOf("uiState: StateFlow<LoginState>"),
        )

        val context = TestContext(
            moduleName = "app",
            styleGuide = StyleGuide(),
        )

        val plan = DefaultTestPlanner().plan(analysis, context, TestType.LOCAL_UNIT)

        assertTrue(plan.scenarios.any { "failure" in it.tags })
    }
}

