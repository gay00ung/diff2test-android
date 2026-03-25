package dev.diff2test.android.kotlinanalyzer

import dev.diff2test.android.core.ChangeSet
import dev.diff2test.android.core.ChangedFile
import dev.diff2test.android.core.ChangedSymbol
import dev.diff2test.android.core.CollaboratorDependency
import dev.diff2test.android.core.SymbolKind
import dev.diff2test.android.core.TargetMethod
import dev.diff2test.android.core.ViewModelAnalysis
import java.nio.file.Files
import java.nio.file.Path

interface ViewModelAnalyzer {
    fun analyze(changeSet: ChangeSet): List<ViewModelAnalysis>
}

class SourceBackedViewModelAnalyzer : ViewModelAnalyzer {
    override fun analyze(changeSet: ChangeSet): List<ViewModelAnalysis> {
        return changeSet.files
            .filter { it.path.fileName.toString().endsWith("ViewModel.kt") }
            .map(::analyzeViewModelFile)
    }
}

@Deprecated(
    message = "Use SourceBackedViewModelAnalyzer instead.",
    replaceWith = ReplaceWith("SourceBackedViewModelAnalyzer()"),
)
class StubViewModelAnalyzer : ViewModelAnalyzer by SourceBackedViewModelAnalyzer()

private fun analyzeViewModelFile(file: ChangedFile): ViewModelAnalysis {
    val resolvedPath = resolveSourcePath(file.path)
    if (!Files.exists(resolvedPath)) {
        return fallbackAnalysis(file)
    }

    val sourceText = Files.readString(resolvedPath)
    return runCatching {
        analyzeViewModelWithPsi(file, resolvedPath, sourceText)
    }.getOrElse { failure ->
        fallbackAnalysis(file).copy(
            filePath = resolvedPath,
            notes = listOf(
                "Fallback analysis used because PSI-backed declaration parsing failed: ${failure::class.simpleName ?: "UnknownError"}",
            ),
        )
    }
}

internal data class ObservableHolder(
    val name: String,
    val kind: String,
    val typeName: String?,
) {
    val rendered: String
        get() = "$name: $kind<${typeName ?: "*"}>"
}

internal fun normalizeTypeName(typeName: String): String {
    return typeName
        .replace("\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .removeSuffix("?")
        .ifBlank { "*" }
}

private fun resolveSourcePath(path: Path): Path {
    if (path.isAbsolute) {
        return path
    }
    return findWorkspaceRoot(Path.of(System.getProperty("user.dir"))).resolve(path).normalize()
}

private fun findWorkspaceRoot(start: Path): Path {
    var current: Path? = start.toAbsolutePath().normalize()

    while (current != null) {
        if (Files.exists(current.resolve("gradlew")) || Files.exists(current.resolve("settings.gradle.kts"))) {
            return current
        }
        current = current.parent
    }

    return start.toAbsolutePath().normalize()
}

private fun fallbackAnalysis(file: ChangedFile): ViewModelAnalysis {
    return ViewModelAnalysis(
        className = file.path.fileName.toString().removeSuffix(".kt"),
        filePath = file.path,
        constructorDependencies = listOf(
            CollaboratorDependency(
                name = "repository",
                type = "Repository",
                role = "data access",
            ),
        ),
        publicMethods = fallbackMethods(file),
        stateHolders = listOf("uiState: StateFlow<*>"),
        primaryStateHolderName = "uiState",
        primaryStateType = null,
        notes = listOf("Fallback analysis used because the target file was not available."),
    )
}

internal fun fallbackMethods(file: ChangedFile): List<TargetMethod> {
    return file.changedSymbols
        .filter { it.kind == SymbolKind.METHOD }
        .ifEmpty {
            listOf(
                ChangedSymbol(
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
}

internal fun inferDependencyRole(type: String): String {
    val normalized = type.trim()
    return when {
        normalized.endsWith("Repository") -> "data access"
        normalized.endsWith("UseCase") -> "use case"
        "Dispatcher" in normalized -> "coroutine scheduling"
        "SavedStateHandle" in normalized -> "state restoration"
        else -> "collaborator"
    }
}
internal val PRIMARY_STATE_KINDS = setOf("StateFlow", "LiveData")

internal val ANDROID_TOUCHPOINTS = listOf(
    "SavedStateHandle",
    "Context",
    "NavController",
    "Fragment",
    "Activity",
    "AndroidViewModel",
)
