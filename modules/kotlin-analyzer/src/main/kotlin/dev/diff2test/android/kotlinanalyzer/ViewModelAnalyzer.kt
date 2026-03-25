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
import kotlin.math.max

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
    val packageName = PACKAGE_PATTERN.find(sourceText)?.groupValues?.get(1).orEmpty()
    val className = parseClassName(sourceText)
        ?: file.path.fileName.toString().removeSuffix(".kt")
    val allObservableHolders = parseObservableHolders(sourceText)
    val observableHolders = allObservableHolders.filterNot { it.name.startsWith("_") }.ifEmpty { allObservableHolders }
    val changedMethodNames = file.changedSymbols
        .filter { it.kind == SymbolKind.METHOD }
        .map { it.name }
        .toSet()
    val publicMethods = parseMethods(sourceText, allObservableHolders)
    val methods = publicMethods
        .filter { changedMethodNames.isEmpty() || it.name in changedMethodNames }
        .ifEmpty { publicMethods.ifEmpty { fallbackMethods(file) } }

    val primaryStateHolder = observableHolders
        .firstOrNull { it.kind in PRIMARY_STATE_KINDS && !it.name.startsWith("_") }
        ?: observableHolders.firstOrNull { it.kind in PRIMARY_STATE_KINDS }

    return ViewModelAnalysis(
        className = className,
        packageName = packageName,
        filePath = resolvedPath,
        constructorDependencies = parseConstructorDependencies(sourceText, className),
        publicMethods = methods,
        stateHolders = observableHolders.map { it.rendered },
        primaryStateHolderName = primaryStateHolder?.name,
        primaryStateType = primaryStateHolder?.typeName,
        androidFrameworkTouchpoints = detectAndroidFrameworkTouchpoints(sourceText),
        notes = listOf(
            "Source-backed declaration analysis without PSI or symbol resolution. Covers constructors, observable holders, and changed public methods.",
        ),
    )
}

private data class ObservableHolder(
    val name: String,
    val kind: String,
    val typeName: String?,
) {
    val rendered: String
        get() = "$name: $kind<${typeName ?: "*"}>"
}

private fun parseClassName(sourceText: String): String? {
    return CLASS_PATTERN.find(sourceText)?.groupValues?.get(1)
}

private fun parseObservableHolders(sourceText: String): List<ObservableHolder> {
    val holders = linkedMapOf<String, ObservableHolder>()

    EXPLICIT_TYPED_OBSERVABLE_PATTERN.findAll(sourceText).forEach { match ->
        val name = match.groupValues[1]
        val kind = match.groupValues[2]
        val typeName = match.groupValues[3].trim()
        holders[name] = ObservableHolder(name = name, kind = kind, typeName = normalizeTypeName(typeName))
    }

    IMPLICIT_MUTABLE_GENERIC_OBSERVABLE_PATTERN.findAll(sourceText).forEach { match ->
        val name = match.groupValues[1]
        val kind = match.groupValues[2].removePrefix("Mutable")
        val typeName = match.groupValues[3].trim()
        holders.putIfAbsent(
            name,
            ObservableHolder(name = name, kind = kind, typeName = normalizeTypeName(typeName)),
        )
    }

    IMPLICIT_MUTABLE_FACTORY_OBSERVABLE_PATTERN.findAll(sourceText).forEach { match ->
        val name = match.groupValues[1]
        val kind = match.groupValues[2].removePrefix("Mutable")
        val constructorValue = match.groupValues[3].trim()
        val typeName = inferTypeFromConstructorValue(constructorValue)
        holders.putIfAbsent(
            name,
            ObservableHolder(name = name, kind = kind, typeName = typeName),
        )
    }

    return holders.values.toList()
}

private fun inferTypeFromConstructorValue(constructorValue: String): String? {
    val value = constructorValue.trim()
    return when {
        value.isBlank() -> null
        CONSTRUCTOR_TYPE_PATTERN.matches(value) -> CONSTRUCTOR_TYPE_PATTERN.matchEntire(value)?.groupValues?.get(1)
        GENERIC_TYPE_PATTERN.matches(value) -> GENERIC_TYPE_PATTERN.matchEntire(value)?.groupValues?.get(1)
        else -> null
    }?.let(::normalizeTypeName)
}

private fun normalizeTypeName(typeName: String): String {
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

private fun fallbackMethods(file: ChangedFile): List<TargetMethod> {
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

private fun parseConstructorDependencies(sourceText: String, className: String): List<CollaboratorDependency> {
    val constructorSection = extractPrimaryConstructorSection(sourceText, className) ?: return emptyList()
    return splitParameters(constructorSection)
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

            val parts = normalized.split(":", limit = 2).map(String::trim)
            if (parts.size != 2) {
                return@mapNotNull null
            }

            CollaboratorDependency(
                name = parts[0],
                type = parts[1].substringBefore("=").trim().removeSuffix(","),
                role = inferDependencyRole(parts[1]),
            )
        }
}

private fun extractPrimaryConstructorSection(sourceText: String, className: String): String? {
    val classStart = sourceText.indexOf("class $className")
    if (classStart == -1) {
        return null
    }

    val openParen = sourceText.indexOf('(', classStart)
    val bodyMarker = sourceText.indexOf('{', classStart).let { if (it == -1) Int.MAX_VALUE else it }
    val extendsMarker = sourceText.indexOf(':', classStart).let { if (it == -1) Int.MAX_VALUE else it }
    if (openParen == -1 || openParen > minOf(bodyMarker, extendsMarker)) {
        return null
    }

    var depth = 0
    for (index in openParen until sourceText.length) {
        when (sourceText[index]) {
            '(' -> depth += 1
            ')' -> {
                depth -= 1
                if (depth == 0) {
                    return sourceText.substring(openParen + 1, index)
                }
            }
        }
    }

    return null
}

private fun splitParameters(parameterBlock: String): List<String> {
    if (parameterBlock.isBlank()) {
        return emptyList()
    }

    val parameters = mutableListOf<String>()
    val builder = StringBuilder()
    var angleDepth = 0
    var parenDepth = 0
    var nullable = false

    parameterBlock.forEach { char ->
        when (char) {
            '<' -> angleDepth += 1
            '>' -> angleDepth = max(0, angleDepth - 1)
            '(' -> parenDepth += 1
            ')' -> parenDepth = max(0, parenDepth - 1)
            '?' -> nullable = true
            ',' -> {
                if (angleDepth == 0 && parenDepth == 0 && !nullable) {
                    parameters += builder.toString()
                    builder.clear()
                    return@forEach
                }
                nullable = false
            }
        }

        builder.append(char)
        if (!char.isWhitespace()) {
            nullable = false
        }
    }

    if (builder.isNotBlank()) {
        parameters += builder.toString()
    }

    return parameters.map(String::trim).filter(String::isNotEmpty)
}

private fun inferDependencyRole(type: String): String {
    val normalized = type.trim()
    return when {
        normalized.endsWith("Repository") -> "data access"
        normalized.endsWith("UseCase") -> "use case"
        "Dispatcher" in normalized -> "coroutine scheduling"
        "SavedStateHandle" in normalized -> "state restoration"
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

private fun parseMethods(sourceText: String, observableHolders: List<ObservableHolder>): List<TargetMethod> {
    val lines = sourceText.lines()
    val parsed = mutableListOf<TargetMethod>()
    val observableNames = observableHolders.map { it.name }.toSet()
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
            val signature = trimmed.substringBefore("{").trimEnd()

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

            if (parsedMethod.isPublic) {
                parsed += TargetMethod(
                    name = parsedMethod.name,
                    signature = parsedMethod.signature,
                    isPublic = true,
                    isSuspend = parsedMethod.isSuspend,
                    mutatesState = mutatesObservableState(parsedMethod.body, observableNames),
                    body = parsedMethod.body,
                )
            }
        }

        braceDepth += countChar(line, '{')
        braceDepth -= countChar(line, '}')
        braceDepth = max(0, braceDepth)
        lineIndex += 1
    }

    return parsed
}

private fun mutatesObservableState(body: String, observableNames: Set<String>): Boolean {
    if (body.isBlank()) {
        return false
    }

    if ("emit(" in body || "tryEmit(" in body || ".value =" in body) {
        return true
    }

    if ("update {" in body || ".postValue(" in body || ".setValue(" in body) {
        return true
    }

    return observableNames.any { name ->
        body.contains("$name.") || body.contains("$name[") || body.contains("$name =")
    }
}

private fun detectAndroidFrameworkTouchpoints(sourceText: String): List<String> {
    return ANDROID_TOUCHPOINTS
        .filter { it in sourceText }
}

private fun countChar(line: String, target: Char): Int = line.count { it == target }

private val PACKAGE_PATTERN = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""", RegexOption.MULTILINE)
private val CLASS_PATTERN = Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)""")
private val METHOD_PATTERN =
    Regex("""^(?:public|private|internal|protected)?\s*(?:operator\s+)?(suspend\s+)?fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
private val EXPLICIT_TYPED_OBSERVABLE_PATTERN = Regex(
    """val\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(StateFlow|SharedFlow|LiveData|Flow)<([^>\n]+(?:>[^>\n]*)?)>""",
)
private val IMPLICIT_MUTABLE_GENERIC_OBSERVABLE_PATTERN = Regex(
    """val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(MutableStateFlow|MutableSharedFlow|MutableLiveData)<([^>\n]+(?:>[^>\n]*)?)>\(""",
)
private val IMPLICIT_MUTABLE_FACTORY_OBSERVABLE_PATTERN = Regex(
    """val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(MutableStateFlow|MutableSharedFlow|MutableLiveData)\(([^)]*)\)""",
)
private val CONSTRUCTOR_TYPE_PATTERN = Regex("""([A-Za-z_][A-Za-z0-9_.<>?]*)\s*\(""")
private val GENERIC_TYPE_PATTERN = Regex("""([A-Za-z_][A-Za-z0-9_.<>?]*)""")

private val PRIMARY_STATE_KINDS = setOf("StateFlow", "LiveData")

private val ANDROID_TOUCHPOINTS = listOf(
    "SavedStateHandle",
    "Context",
    "NavController",
    "Fragment",
    "Activity",
    "AndroidViewModel",
)
