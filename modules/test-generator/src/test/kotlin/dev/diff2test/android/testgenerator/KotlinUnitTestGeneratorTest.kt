package dev.diff2test.android.testgenerator

import dev.diff2test.android.core.CollaboratorDependency
import dev.diff2test.android.core.RiskLevel
import dev.diff2test.android.core.StyleGuide
import dev.diff2test.android.core.TargetMethod
import dev.diff2test.android.core.TestContext
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.core.TestScenario
import dev.diff2test.android.core.TestType
import dev.diff2test.android.core.ViewModelAnalysis
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains

class KotlinUnitTestGeneratorTest {
    @Test
    fun `imports and instantiates saved state handle for generated search tests`() {
        val repoRoot = findRepoRoot()
        val analysis = ViewModelAnalysis(
            className = "SearchViewModel",
            packageName = "com.example.search",
            filePath = repoRoot.resolve("fixtures/sample-app/app/src/main/java/com/example/search/SearchViewModel.kt"),
            constructorDependencies = listOf(
                CollaboratorDependency(name = "savedStateHandle", type = "SavedStateHandle"),
                CollaboratorDependency(name = "repository", type = "SearchRepository"),
                CollaboratorDependency(name = "ioDispatcher", type = "CoroutineDispatcher"),
            ),
            publicMethods = listOf(
                TargetMethod(name = "onQueryChanged", signature = "fun onQueryChanged(value: String)", mutatesState = true),
            ),
            stateHolders = listOf("uiState: StateFlow<SearchUiState>"),
            primaryStateHolderName = "uiState",
            primaryStateType = "SearchUiState",
        )
        val plan = TestPlan(
            targetClass = "SearchViewModel",
            targetMethods = listOf("onQueryChanged"),
            testType = TestType.LOCAL_UNIT,
            scenarios = listOf(
                TestScenario(
                    name = "onQueryChanged updates state on success",
                    goal = "happy path",
                    expectedOutcome = "state updated",
                ),
            ),
            requiredFakes = emptyList(),
            assertions = emptyList(),
            riskLevel = RiskLevel.LOW,
        )

        val bundle = KotlinUnitTestGenerator().generate(
            plan = plan,
            context = TestContext(moduleName = "app", styleGuide = StyleGuide()),
            analysis = analysis,
        )

        val content = bundle.files.single().content
        assertContains(content, "import androidx.lifecycle.SavedStateHandle")
        assertContains(content, "savedStateHandle = SavedStateHandle()")
    }

    private fun findRepoRoot(): Path {
        var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

        while (current != null) {
            if (Files.exists(current.resolve("fixtures/sample-app"))) {
                return current
            }
            current = current.parent
        }

        error("Could not locate repository root from ${System.getProperty("user.dir")}")
    }
}
