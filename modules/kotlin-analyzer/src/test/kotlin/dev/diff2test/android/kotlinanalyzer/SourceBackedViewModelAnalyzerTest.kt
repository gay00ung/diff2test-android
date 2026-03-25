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
            "Compiler-backed symbol resolution is enabled",
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

    @Test
    fun `resolves imported typealiases inside the same module`() {
        val moduleRoot = Files.createTempDirectory("d2t-analyzer-alias")
        val sourceRoot = moduleRoot.resolve("src/main/kotlin")

        val aliasesFile = sourceRoot.resolve("com/example/types/Aliases.kt")
        Files.createDirectories(aliasesFile.parent)
        Files.writeString(
            aliasesFile,
            """
            package com.example.types

            import kotlinx.coroutines.flow.StateFlow
            import com.example.auth.SignUpUiState
            import com.example.auth.RegisterUserUseCase

            typealias RegistrationUseCase = RegisterUserUseCase
            typealias SignUpStateStream = StateFlow<SignUpUiState>
            """.trimIndent(),
        )

        val supportFile = sourceRoot.resolve("com/example/auth/Support.kt")
        Files.createDirectories(supportFile.parent)
        Files.writeString(
            supportFile,
            """
            package com.example.auth

            class RegisterUserUseCase
            data class SignUpUiState(val isRegistered: Boolean = false)
            """.trimIndent(),
        )

        val viewModelFile = sourceRoot.resolve("com/example/auth/AliasedViewModel.kt")
        Files.writeString(
            viewModelFile,
            """
            package com.example.auth

            import androidx.lifecycle.ViewModel
            import com.example.types.RegistrationUseCase
            import com.example.types.SignUpStateStream
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.asStateFlow

            class AliasedViewModel(
                private val registerUser: RegistrationUseCase,
            ) : ViewModel() {
                private val _uiState = MutableStateFlow(SignUpUiState())
                val uiState: SignUpStateStream = _uiState.asStateFlow()

                fun submit() {
                    _uiState.value = _uiState.value.copy(isRegistered = true)
                }
            }
            """.trimIndent(),
        )

        val changeSet = ChangeSet(
            source = ChangeSource.GIT_DIFF,
            files = listOf(
                ChangedFile(
                    path = viewModelFile,
                    changedSymbols = listOf(
                        ChangedSymbol(name = "submit", kind = SymbolKind.METHOD),
                    ),
                ),
            ),
        )

        val analysis = analyzer.analyze(changeSet).single()

        assertEquals(listOf("RegisterUserUseCase"), analysis.constructorDependencies.map { it.type })
        assertEquals(listOf("uiState: StateFlow<SignUpUiState>"), analysis.stateHolders)
        assertEquals("SignUpUiState", analysis.primaryStateType)
    }

    @Test
    fun `resolves generic typealiases with compiler-backed analysis`() {
        val moduleRoot = Files.createTempDirectory("d2t-analyzer-generic-alias")
        val sourceRoot = moduleRoot.resolve("src/main/kotlin")

        val aliasesFile = sourceRoot.resolve("com/example/types/Aliases.kt")
        Files.createDirectories(aliasesFile.parent)
        Files.writeString(
            aliasesFile,
            """
            package com.example.types

            import kotlinx.coroutines.flow.StateFlow

            typealias ResultState<T> = StateFlow<Result<T>>
            """.trimIndent(),
        )

        val viewModelFile = sourceRoot.resolve("com/example/auth/GenericAliasViewModel.kt")
        Files.createDirectories(viewModelFile.parent)
        Files.writeString(
            viewModelFile,
            """
            package com.example.auth

            import androidx.lifecycle.ViewModel
            import com.example.types.ResultState
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.asStateFlow

            data class GenericUiState(val query: String = "")

            class GenericAliasViewModel : ViewModel() {
                private val _uiState = MutableStateFlow(Result.success(GenericUiState()))
                val uiState: ResultState<GenericUiState> = _uiState.asStateFlow()

                fun clear() {
                    _uiState.value = Result.success(GenericUiState())
                }
            }
            """.trimIndent(),
        )

        val changeSet = ChangeSet(
            source = ChangeSource.GIT_DIFF,
            files = listOf(
                ChangedFile(
                    path = viewModelFile,
                    changedSymbols = listOf(
                        ChangedSymbol(name = "clear", kind = SymbolKind.METHOD),
                    ),
                ),
            ),
        )

        val analysis = analyzer.analyze(changeSet).single()

        assertEquals(listOf("uiState: StateFlow<Result<GenericUiState>>"), analysis.stateHolders)
        assertEquals("Result<GenericUiState>", analysis.primaryStateType)
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
