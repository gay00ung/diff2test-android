package dev.diff2test.android.kotlinanalyzer

import dev.diff2test.android.core.ChangeSet
import dev.diff2test.android.core.ChangeSource
import dev.diff2test.android.core.ChangedFile
import dev.diff2test.android.core.ChangedSymbol
import dev.diff2test.android.core.SymbolKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceBackedViewModelAnalyzerTest {
    private val repoRoot = findRepoRoot()
    private val analyzer = SourceBackedViewModelAnalyzer()

    @Test
    fun `analyzes state holders collaborators and changed methods for signup viewmodel`() {
        val target = repoRoot.resolve("fixtures/sample-app/app/src/main/java/com/example/auth/SignUpViewModel.kt")
        val changeSet = ChangeSet(
            source = ChangeSource.GIT_DIFF,
            files = listOf(
                ChangedFile(
                    path = target,
                    changedSymbols = listOf(
                        ChangedSymbol(name = "onEmailChanged", kind = SymbolKind.METHOD),
                        ChangedSymbol(name = "submitRegistration", kind = SymbolKind.METHOD),
                    ),
                ),
            ),
        )

        val analysis = analyzer.analyze(changeSet).single()

        assertEquals("SignUpViewModel", analysis.className)
        assertEquals("com.example.auth", analysis.packageName)
        assertEquals(listOf("RegisterUserUseCase", "CoroutineDispatcher"), analysis.constructorDependencies.map { it.type })
        assertEquals(listOf("uiState: StateFlow<SignUpUiState>", "events: SharedFlow<SignUpEvent>"), analysis.stateHolders)
        assertEquals("uiState", analysis.primaryStateHolderName)
        assertEquals("SignUpUiState", analysis.primaryStateType)
        assertEquals(listOf("onEmailChanged", "submitRegistration"), analysis.publicMethods.map { it.name })
        assertTrue(analysis.publicMethods.all { it.mutatesState })
        assertContains(
            analysis.notes.single(),
            "Source-backed declaration analysis without PSI or symbol resolution",
        )
    }

    @Test
    fun `detects saved state handle touchpoint in search fixture`() {
        val target = repoRoot.resolve("fixtures/sample-app/app/src/main/java/com/example/search/SearchViewModel.kt")
        val changeSet = ChangeSet(
            source = ChangeSource.GIT_DIFF,
            files = listOf(ChangedFile(path = target)),
        )

        val analysis = analyzer.analyze(changeSet).single()

        assertContains(analysis.androidFrameworkTouchpoints, "SavedStateHandle")
        assertTrue(analysis.constructorDependencies.any { it.type == "SavedStateHandle" && it.role == "state restoration" })
        assertEquals("uiState", analysis.primaryStateHolderName)
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
