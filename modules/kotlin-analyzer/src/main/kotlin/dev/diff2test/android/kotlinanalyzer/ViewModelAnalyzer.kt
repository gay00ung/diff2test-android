package dev.diff2test.android.kotlinanalyzer

import dev.diff2test.android.core.ChangeSet
import dev.diff2test.android.core.ChangedFile
import dev.diff2test.android.core.CollaboratorDependency
import dev.diff2test.android.core.SymbolKind
import dev.diff2test.android.core.TargetMethod
import dev.diff2test.android.core.ViewModelAnalysis
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max

interface ViewModelAnalyzer {
    fun analyze(changeSet: ChangeSet): List<ViewModelAnalysis>
}

class StubViewModelAnalyzer : ViewModelAnalyzer {
    override fun analyze(changeSet: ChangeSet): List<ViewModelAnalysis> {
        return changeSet.files
            .filter { it.path.fileName.toString().endsWith("ViewModel.kt") }
            .map(::analyzeViewModelFile)
    }
}

private fun analyzeViewModelFile(file: ChangedFile): ViewModelAnalysis {
    val resolvedPath = resolveSourcePath(file.path)
    if (!Files.exists(resolvedPath)) {
        return fallbackAnalysis(file)
    }

    val sourceText = Files.readString(resolvedPath)
    val packageName = PACKAGE_PATTERN.find(sourceText)?.groupValues?.get(1).orEmpty()
    val className = CLASS_PATTERN.find(sourceText)?.groupValues?.get(1)
        ?: file.path.fileName.toString().removeSuffix(".kt")
    val stateHolder = STATE_HOLDER_PATTERN.find(sourceText)
    val stateHolderName = stateHolder?.groupValues?.get(1)
    val stateType = stateHolder?.groupValues?.get(2)
    val changedMethodNames = file.changedSymbols
        .filter { it.kind == SymbolKind.METHOD }
        .map { it.name }
        .toSet()

    val parsedMethods = parseMethods(sourceText)
    val methods = parsedMethods
        .filter { changedMethodNames.isEmpty() || it.name in changedMethodNames }
        .ifEmpty { parsedMethods.filter { it.isPublic }.takeIf { it.isNotEmpty() } ?: fallbackMethods(file) }

    return ViewModelAnalysis(
        className = className,
        packageName = packageName,
        filePath = resolvedPath,
        constructorDependencies = parseConstructorDependencies(sourceText, className),
        publicMethods = methods,
        stateHolders = listOfNotNull(
            if (stateHolderName != null && stateType != null) "$stateHolderName: StateFlow<$stateType>" else null,
        ),
        primaryStateHolderName = stateHolderName,
        primaryStateType = stateType,
        androidFrameworkTouchpoints = detectAndroidFrameworkTouchpoints(sourceText),
        notes = listOf("Source-backed heuristic analysis. Replace with PSI or symbol resolution for production fidelity."),
    )
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

private fun fallbackMethods(file: ChangedFile): List<TargetMethod> {
    return file.changedSymbols
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
}

private fun parseConstructorDependencies(sourceText: String, className: String): List<CollaboratorDependency> {
    val classMatch = CLASS_WITH_CONSTRUCTOR_PATTERN.find(sourceText) ?: return emptyList()
    if (classMatch.groupValues[1] != className) {
        return emptyList()
    }

    return classMatch.groupValues[2]
        .split(",")
        .mapNotNull { parameter ->
            val normalized = parameter
                .replace("\n", " ")
                .trim()
                .removePrefix("private ")
                .removePrefix("internal ")
                .removePrefix("protected ")
                .removePrefix("public ")
                .removePrefix("val ")
                .removePrefix("var ")

            val parts = normalized.split(":").map(String::trim)
            if (parts.size != 2) {
                return@mapNotNull null
            }

            CollaboratorDependency(
                name = parts[0],
                type = parts[1].removeSuffix(","),
                role = inferDependencyRole(parts[1]),
            )
        }
}

private fun inferDependencyRole(type: String): String {
    return when {
        type.endsWith("Repository") -> "data access"
        type.endsWith("UseCase") -> "use case"
        "Dispatcher" in type -> "coroutine scheduling"
        else -> "collaborator"
    }
}

private data class ParsedMethod(
    val name: String,
    val signature: String,
    val isPublic: Boolean,
    val isSuspend: Boolean,
    val body: String,
)

private fun parseMethods(sourceText: String): List<TargetMethod> {
    val lines = sourceText.lines()
    val parsed = mutableListOf<TargetMethod>()
    var braceDepth = 0
    var lineIndex = 0

    while (lineIndex < lines.size) {
        val line = lines[lineIndex]
        val trimmed = line.trim()
        val depthBefore = braceDepth
        val methodMatch = METHOD_PATTERN.find(trimmed)

        if (methodMatch != null) {
            val name = methodMatch.groupValues[2]
            val isSuspend = methodMatch.groupValues[1].isNotBlank()
            val isPublic = !trimmed.startsWith("private ") &&
                !trimmed.startsWith("internal ") &&
                !trimmed.startsWith("protected ")
            val signature = trimmed

            val parsedMethod = if (trimmed.contains("=") && !trimmed.contains("{")) {
                ParsedMethod(
                    name = name,
                    signature = signature,
                    isPublic = isPublic,
                    isSuspend = isSuspend,
                    body = trimmed.substringAfter("=").trim(),
                )
            } else {
                val bodyLines = mutableListOf<String>()
                var localDepth = braceDepth + countChar(line, '{') - countChar(line, '}')
                bodyLines += line
                var methodLineIndex = lineIndex + 1

                while (methodLineIndex < lines.size && localDepth > depthBefore) {
                    val bodyLine = lines[methodLineIndex]
                    bodyLines += bodyLine
                    localDepth += countChar(bodyLine, '{')
                    localDepth -= countChar(bodyLine, '}')
                    methodLineIndex += 1
                }

                lineIndex = max(lineIndex, methodLineIndex - 1)
                ParsedMethod(
                    name = name,
                    signature = signature,
                    isPublic = isPublic,
                    isSuspend = isSuspend,
                    body = bodyLines.joinToString("\n"),
                )
            }

            parsed += TargetMethod(
                name = parsedMethod.name,
                signature = parsedMethod.signature,
                isPublic = parsedMethod.isPublic,
                isSuspend = parsedMethod.isSuspend,
                mutatesState = "_uiState" in parsedMethod.body || "uiState" in parsedMethod.body || "emit(" in parsedMethod.body,
                body = parsedMethod.body,
            )
        }

        braceDepth += countChar(line, '{')
        braceDepth -= countChar(line, '}')
        braceDepth = max(0, braceDepth)
        lineIndex += 1
    }

    return parsed
}

private fun detectAndroidFrameworkTouchpoints(sourceText: String): List<String> {
    return ANDROID_TOUCHPOINTS
        .filter { it in sourceText }
}

private fun countChar(line: String, target: Char): Int = line.count { it == target }

private val PACKAGE_PATTERN = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""", RegexOption.MULTILINE)
private val CLASS_PATTERN = Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)""")
private val CLASS_WITH_CONSTRUCTOR_PATTERN =
    Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)\s*\((.*?)\)\s*:\s*ViewModel\(""", setOf(RegexOption.DOT_MATCHES_ALL))
private val METHOD_PATTERN =
    Regex("""^(?:public|private|internal|protected)?\s*(suspend\s+)?fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
private val STATE_HOLDER_PATTERN =
    Regex("""val\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*StateFlow<([A-Za-z_][A-Za-z0-9_.<>?]*)>""")

private val ANDROID_TOUCHPOINTS = listOf(
    "SavedStateHandle",
    "Context",
    "NavController",
    "Fragment",
    "Activity",
    "AndroidViewModel",
)
