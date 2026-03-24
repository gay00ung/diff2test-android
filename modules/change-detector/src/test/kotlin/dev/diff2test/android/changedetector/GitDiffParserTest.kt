package dev.diff2test.android.changedetector

import dev.diff2test.android.core.ChangedSymbol
import dev.diff2test.android.core.SymbolKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitDiffParserTest {
    @Test
    fun `parses changed files hunks and Kotlin symbol candidates`() {
        val diff = """
            diff --git a/app/src/main/java/com/example/LoginViewModel.kt b/app/src/main/java/com/example/LoginViewModel.kt
            index 1111111..2222222 100644
            --- a/app/src/main/java/com/example/LoginViewModel.kt
            +++ b/app/src/main/java/com/example/LoginViewModel.kt
            @@ -10,0 +11,4 @@ class LoginViewModel
            +    suspend fun loadData() {
            +    }
            +    val uiState = MutableStateFlow(LoginState())
            @@ -20 +25 @@ class LoginViewModel
            -    fun oldCall() = Unit
            +    fun refresh() = Unit
        """.trimIndent()

        val files = parseGitDiff(diff)

        assertEquals(1, files.size)
        assertEquals(Path.of("app/src/main/java/com/example/LoginViewModel.kt"), files.first().path)
        assertEquals(2, files.first().hunks.size)
        assertTrue(files.first().changedSymbols.any { it.name == "loadData" && it.kind == SymbolKind.METHOD })
        assertTrue(files.first().changedSymbols.any { it.name == "refresh" && it.kind == SymbolKind.METHOD })
        assertTrue(files.first().changedSymbols.any { it.name == "uiState" && it.kind == SymbolKind.STATE })
    }

    @Test
    fun `maps changed lines to enclosing methods in source file`() {
        val file = Files.createTempFile("signup-viewmodel", ".kt")
        Files.writeString(
            file,
            """
            class SignUpViewModel {
                fun onEmailChanged(value: String) {
                    val normalized = value.trim().lowercase()
                    println(normalized)
                }

                fun clearError() {
                    println("clear")
                }

                fun submitRegistration() {
                    println("submit")
                }
            }
            """.trimIndent(),
        )

        val symbols = resolveSymbolsFromChangedLines(
            sourcePath = file,
            changedNewLines = setOf(3, 7, 11),
            inlineCandidates = linkedSetOf(
                ChangedSymbol(name = "clearError", kind = SymbolKind.METHOD, signature = "fun clearError()"),
            ),
        )

        assertTrue(symbols.any { it.name == "onEmailChanged" && it.kind == SymbolKind.METHOD })
        assertTrue(symbols.any { it.name == "clearError" && it.kind == SymbolKind.METHOD })
        assertTrue(symbols.any { it.name == "submitRegistration" && it.kind == SymbolKind.METHOD })
    }
}
