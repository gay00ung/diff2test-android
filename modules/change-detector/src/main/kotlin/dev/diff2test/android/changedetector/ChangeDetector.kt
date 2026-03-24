package dev.diff2test.android.changedetector

import dev.diff2test.android.core.ChangeSet
import dev.diff2test.android.core.ChangeSource
import dev.diff2test.android.core.ChangedFile
import dev.diff2test.android.core.ChangedSymbol
import dev.diff2test.android.core.SymbolKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max

data class ScanRequest(
    val baseRef: String? = "HEAD",
    val headRef: String? = null,
    val source: ChangeSource = ChangeSource.GIT_DIFF,
    val workingDirectory: Path = Path.of(System.getProperty("user.dir")),
)

interface ChangeDetector {
    fun scan(request: ScanRequest = ScanRequest()): ChangeSet
}

class GitDiffChangeDetector : ChangeDetector {
    override fun scan(request: ScanRequest): ChangeSet {
        val command = buildGitDiffCommand(request)
        val process = ProcessBuilder(command)
            .directory(request.workingDirectory.toFile())
            .start()

        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            return ChangeSet(
                source = request.source,
                baseRef = request.baseRef,
                headRef = request.headRef,
                summary = "git diff failed: ${stderr.ifBlank { "unknown error" }}",
            )
        }

        val files = parseGitDiff(stdout, request.workingDirectory)
        val symbolCount = files.sumOf { it.changedSymbols.size }
        val summary = if (files.isEmpty()) {
            "No git diff changes detected."
        } else {
            "Detected ${files.size} changed file(s) and $symbolCount Kotlin symbol candidate(s)."
        }

        return ChangeSet(
            source = request.source,
            baseRef = request.baseRef,
            headRef = request.headRef,
            files = files,
            summary = summary,
        )
    }
}

internal fun buildGitDiffCommand(request: ScanRequest): List<String> {
    return buildList {
        add("git")
        add("diff")
        add("--no-color")
        add("--unified=0")
        request.baseRef?.let(::add)
        request.headRef?.let(::add)
        add("--")
    }
}

internal fun parseGitDiff(
    diffText: String,
    workingDirectory: Path = Path.of("."),
): List<ChangedFile> {
    if (diffText.isBlank()) {
        return emptyList()
    }

    val files = mutableListOf<ChangedFile>()
    var currentPath: Path? = null
    var currentHunks = mutableListOf<String>()
    var currentSymbols = linkedSetOf<ChangedSymbol>()
    var currentHunkLines = mutableListOf<String>()
    var currentChangedNewLines = linkedSetOf<Int>()
    var currentNewLine = 0
    var currentOldLine = 0

    fun flushHunk() {
        if (currentHunkLines.isNotEmpty()) {
            currentHunks += currentHunkLines.joinToString("\n")
            currentHunkLines = mutableListOf()
        }
    }

    fun flushFile() {
        flushHunk()
        val path = currentPath ?: return
        val sourcePath = if (path.isAbsolute) path else workingDirectory.resolve(path).normalize()
        val resolvedSymbols = if (path.toString().endsWith(".kt")) {
            resolveSymbolsFromChangedLines(sourcePath, currentChangedNewLines, currentSymbols)
        } else {
            currentSymbols.toList()
        }
        files += ChangedFile(
            path = path,
            hunks = currentHunks.toList(),
            changedSymbols = resolvedSymbols,
        )
        currentPath = null
        currentHunks = mutableListOf()
        currentSymbols = linkedSetOf()
        currentChangedNewLines = linkedSetOf()
    }

    diffText.lineSequence().forEach { line ->
        when {
            line.startsWith("diff --git ") -> {
                flushFile()
                currentPath = parsePathFromDiffHeader(line)
            }

            line.startsWith("@@") -> {
                flushHunk()
                currentHunkLines += line
                val (oldStart, newStart) = parseHunkHeader(line)
                currentOldLine = oldStart
                currentNewLine = newStart
            }

            line.startsWith("+") && !line.startsWith("+++") -> {
                currentHunkLines += line
                detectChangedSymbolCandidate(line)?.let(currentSymbols::add)
                currentChangedNewLines += currentNewLine
                currentNewLine += 1
            }

            line.startsWith("-") && !line.startsWith("---") -> {
                currentHunkLines += line
                detectChangedSymbolCandidate(line)?.let(currentSymbols::add)
                currentOldLine += 1
            }

            line.startsWith(" ") -> {
                currentHunkLines += line
                currentOldLine += 1
                currentNewLine += 1
            }
        }
    }

    flushFile()
    return files
}

private fun parseHunkHeader(header: String): Pair<Int, Int> {
    val match = HUNK_PATTERN.find(header) ?: return 0 to 0
    val oldStart = match.groupValues[1].toInt()
    val newStart = match.groupValues[2].toInt()
    return oldStart to newStart
}

private fun parsePathFromDiffHeader(header: String): Path? {
    val parts = header.split(" ")
    if (parts.size < 4) {
        return null
    }

    val candidate = parts[3]
        .removePrefix("b/")
        .removePrefix("\"")
        .removeSuffix("\"")

    return Path.of(candidate)
}

fun extractKotlinSymbols(sourceText: String): List<ChangedSymbol> {
    return sourceText.lineSequence()
        .mapNotNull(::detectChangedSymbolCandidate)
        .distinct()
        .toList()
}

fun extractKotlinSymbols(sourcePath: Path): List<ChangedSymbol> {
    if (!Files.exists(sourcePath)) {
        return emptyList()
    }
    return extractKotlinSymbols(Files.readString(sourcePath))
}

internal fun resolveSymbolsFromChangedLines(
    sourcePath: Path,
    changedNewLines: Set<Int>,
    inlineCandidates: Set<ChangedSymbol>,
): List<ChangedSymbol> {
    if (!Files.exists(sourcePath)) {
        return inlineCandidates.toList()
    }

    val declarations = parseDeclarations(Files.readAllLines(sourcePath))
    if (declarations.isEmpty() || changedNewLines.isEmpty()) {
        return inlineCandidates.toList()
    }

    val resolved = linkedSetOf<ChangedSymbol>()
    resolved += inlineCandidates

    changedNewLines.forEach { changedLine ->
        declarations
            .filter { changedLine in it.startLine..it.endLine }
            .sortedWith(compareBy<Declaration>({ declarationPriority(it.kind) }, { it.endLine - it.startLine }))
            .firstOrNull()
            ?.let { declaration ->
                resolved += ChangedSymbol(
                    name = declaration.name,
                    kind = declaration.kind,
                    signature = declaration.signature,
                )
            }
    }

    return resolved.toList()
}

private data class Declaration(
    val name: String,
    val kind: SymbolKind,
    val signature: String,
    val startLine: Int,
    var endLine: Int,
)

private data class OpenDeclaration(
    val declaration: Declaration,
    val targetDepth: Int,
)

private fun parseDeclarations(lines: List<String>): List<Declaration> {
    val declarations = mutableListOf<Declaration>()
    val openDeclarations = ArrayDeque<OpenDeclaration>()
    var braceDepth = 0

    lines.forEachIndexed { index, rawLine ->
        val lineNumber = index + 1
        val line = rawLine.trim()
        val depthBefore = braceDepth

        val declaration = detectDeclaration(line, lineNumber)
        if (declaration != null) {
            declarations += declaration
        }

        braceDepth += countChar(rawLine, '{')
        braceDepth -= countChar(rawLine, '}')
        braceDepth = max(0, braceDepth)

        if (declaration != null) {
            val opensBlock = rawLine.contains("{")
            if (declaration.kind == SymbolKind.METHOD || declaration.kind == SymbolKind.CLASS) {
                if (opensBlock) {
                    openDeclarations.addLast(
                        OpenDeclaration(
                            declaration = declaration,
                            targetDepth = depthBefore,
                        ),
                    )
                } else {
                    declaration.endLine = lineNumber
                }
            } else {
                declaration.endLine = lineNumber
            }
        }

        while (openDeclarations.isNotEmpty() && braceDepth <= openDeclarations.last().targetDepth) {
            val open = openDeclarations.removeLast()
            open.declaration.endLine = lineNumber
        }
    }

    while (openDeclarations.isNotEmpty()) {
        val open = openDeclarations.removeLast()
        open.declaration.endLine = lines.size
    }

    return declarations
}

private fun detectDeclaration(line: String, lineNumber: Int): Declaration? {
    val classMatch = CLASS_PATTERN.find(line)
    if (classMatch != null) {
        return Declaration(
            name = classMatch.groupValues[2],
            kind = SymbolKind.CLASS,
            signature = line,
            startLine = lineNumber,
            endLine = lineNumber,
        )
    }

    val methodMatch = METHOD_PATTERN.find(line)
    if (methodMatch != null) {
        return Declaration(
            name = methodMatch.groupValues[1],
            kind = SymbolKind.METHOD,
            signature = line,
            startLine = lineNumber,
            endLine = lineNumber,
        )
    }

    val propertyMatch = PROPERTY_PATTERN.find(line)
    if (propertyMatch != null) {
        val propertyName = propertyMatch.groupValues[1]
        return Declaration(
            name = propertyName,
            kind = if ("state" in propertyName.lowercase()) SymbolKind.STATE else SymbolKind.PROPERTY,
            signature = line,
            startLine = lineNumber,
            endLine = lineNumber,
        )
    }

    return null
}

private fun countChar(line: String, target: Char): Int = line.count { it == target }

private fun declarationPriority(kind: SymbolKind): Int {
    return when (kind) {
        SymbolKind.METHOD -> 0
        SymbolKind.STATE -> 1
        SymbolKind.PROPERTY -> 2
        SymbolKind.CLASS -> 3
    }
}

private fun detectChangedSymbolCandidate(line: String): ChangedSymbol? {
    val body = line.removePrefix("+").removePrefix("-").trim()

    val classMatch = CLASS_PATTERN.find(body)
    if (classMatch != null) {
        return ChangedSymbol(
            name = classMatch.groupValues[2],
            kind = SymbolKind.CLASS,
            signature = body,
        )
    }

    val methodMatch = METHOD_PATTERN.find(body)
    if (methodMatch != null) {
        return ChangedSymbol(
            name = methodMatch.groupValues[1],
            kind = SymbolKind.METHOD,
            signature = body,
        )
    }

    val propertyMatch = PROPERTY_PATTERN.find(body)
    if (propertyMatch != null) {
        val propertyName = propertyMatch.groupValues[1]
        val kind = if ("state" in propertyName.lowercase()) SymbolKind.STATE else SymbolKind.PROPERTY
        return ChangedSymbol(
            name = propertyName,
            kind = kind,
            signature = body,
        )
    }

    return null
}

private val CLASS_PATTERN =
    Regex("""^(?:public|private|internal|protected)?\s*(?:data\s+|sealed\s+|abstract\s+|open\s+)?(class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)""")

private val METHOD_PATTERN =
    Regex("""^(?:public|private|internal|protected)?\s*(?:override\s+)?(?:suspend\s+)?fun\s+([A-Za-z_][A-Za-z0-9_]*)""")

private val PROPERTY_PATTERN =
    Regex("""^(?:public|private|internal|protected)?\s*(?:override\s+)?(?:lateinit\s+)?(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)""")

private val HUNK_PATTERN =
    Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*$""")
