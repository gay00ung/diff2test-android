package dev.diff2test.android.kotlinanalyzer

import dev.diff2test.android.core.ChangeSet
import dev.diff2test.android.core.CollaboratorDependency
import dev.diff2test.android.core.SymbolKind
import dev.diff2test.android.core.TargetMethod
import dev.diff2test.android.core.ViewModelAnalysis

interface ViewModelAnalyzer {
    fun analyze(changeSet: ChangeSet): List<ViewModelAnalysis>
}

class StubViewModelAnalyzer : ViewModelAnalyzer {
    override fun analyze(changeSet: ChangeSet): List<ViewModelAnalysis> {
        return changeSet.files
            .filter { it.path.fileName.toString().endsWith("ViewModel.kt") }
            .map { file ->
                val methods = file.changedSymbols
                    .filter { it.kind == SymbolKind.METHOD }
                    .ifEmpty {
                        listOf(
                            dev.diff2test.android.core.ChangedSymbol(
                                name = "refresh",
                                kind = SymbolKind.METHOD,
                                signature = "fun refresh()",
                            ),
                        )
                    }
                    .map { symbol ->
                        TargetMethod(
                            name = symbol.name,
                            signature = symbol.signature ?: "fun ${symbol.name}()",
                            isPublic = true,
                            isSuspend = symbol.signature?.contains("suspend") == true,
                            mutatesState = true,
                        )
                    }

                ViewModelAnalysis(
                    className = file.path.fileName.toString().removeSuffix(".kt"),
                    filePath = file.path,
                    constructorDependencies = listOf(
                        CollaboratorDependency(
                            name = "repository",
                            type = "Repository",
                            role = "data access",
                        ),
                    ),
                    publicMethods = methods,
                    stateHolders = listOf("uiState: StateFlow<*>"),
                    notes = listOf("Replace stub analysis with PSI or symbol resolution in v1 engine."),
                )
            }
    }
}

