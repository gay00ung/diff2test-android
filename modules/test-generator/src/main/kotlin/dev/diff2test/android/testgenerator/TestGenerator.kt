package dev.diff2test.android.testgenerator

import dev.diff2test.android.core.GeneratedFile
import dev.diff2test.android.core.GeneratedTestBundle
import dev.diff2test.android.core.TargetMethod
import dev.diff2test.android.core.TestContext
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.core.ViewModelAnalysis
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.max

interface TestGenerator {
    fun generate(plan: TestPlan, context: TestContext, analysis: ViewModelAnalysis): GeneratedTestBundle
}

class KotlinUnitTestGenerator : TestGenerator {
    override fun generate(plan: TestPlan, context: TestContext, analysis: ViewModelAnalysis): GeneratedTestBundle {
        val packageName = analysis.packageName.ifBlank { "dev.diff2test.android.generated" }
        val generatedClassName = "${plan.targetClass}GeneratedTest"
        val relativePath = Path.of(
            "src/test/kotlin/" + packageName.replace('.', '/') + "/$generatedClassName.kt",
        )
        val helper = GeneratedTestHelperBuilder(analysis)
        val testMethods = buildTestMethods(analysis)
        val warnings = mutableListOf<String>()

        if (helper.warnings.isNotEmpty()) {
            warnings += helper.warnings
        }
        if (testMethods.isEmpty()) {
            warnings += "No concrete test heuristics matched. Falling back to placeholder generation."
        }

        val content = buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import kotlin.test.Test")
            appendLine("import kotlin.test.assertEquals")
            appendLine("import kotlin.test.assertNotNull")
            appendLine("import kotlin.test.assertNull")
            appendLine("import kotlin.test.assertTrue")
            appendLine("import kotlinx.coroutines.ExperimentalCoroutinesApi")
            appendLine("import kotlinx.coroutines.test.StandardTestDispatcher")
            appendLine("import kotlinx.coroutines.test.advanceUntilIdle")
            appendLine("import kotlinx.coroutines.test.runTest")
            helper.imports.sorted().forEach { importLine ->
                appendLine(importLine)
            }
            appendLine()
            appendLine("@OptIn(ExperimentalCoroutinesApi::class)")
            appendLine("class $generatedClassName {")
            appendLine()
            appendLine("    private val testDispatcher = StandardTestDispatcher()")
            appendLine()

            helper.declarations.forEach { declaration ->
                declaration.lines().forEach { declarationLine ->
                    appendLine("    $declarationLine")
                }
                appendLine()
            }

            appendLine("    private fun createViewModel(): ${analysis.className} {")
            appendLine("        return ${analysis.className}(")
            helper.constructorArguments.forEachIndexed { index, argument ->
                val suffix = if (index == helper.constructorArguments.lastIndex) "" else ","
                appendLine("            $argument$suffix")
            }
            appendLine("        )")
            appendLine("    }")
            appendLine()

            if (testMethods.isNotEmpty()) {
                testMethods.forEach { method ->
                    appendLine(method.prettified(indent = "    "))
                    appendLine()
                }
            } else {
                plan.scenarios.forEach { scenario ->
                    val testName = scenario.name.replace('`', '\'')
                    appendLine("    @Test")
                    appendLine("    fun `$testName`() {")
                    appendLine("        // Replace this placeholder with project-specific setup and assertions.")
                    appendLine("        assertTrue(true, ${quote(scenario.expectedOutcome)})")
                    appendLine("    }")
                    appendLine()
                }
            }

            appendLine("}")
        }

        return GeneratedTestBundle(
            plan = plan,
            files = listOf(GeneratedFile(relativePath = relativePath, content = content)),
            warnings = warnings,
        )
    }
}

private data class RenderedTestMethod(
    val lines: List<String>,
) {
    fun prettified(indent: String): String {
        return lines.joinToString("\n") { "$indent$it" }
    }
}

private fun buildTestMethods(analysis: ViewModelAnalysis): List<RenderedTestMethod> {
    val methods = mutableListOf<RenderedTestMethod>()
    val stateHolder = analysis.primaryStateHolderName ?: "uiState"
    val successAction = analysis.publicMethods.firstOrNull(::isActionMethod)

    if (analysis.primaryStateType != null) {
        methods += RenderedTestMethod(
            lines = listOf(
                "@Test",
                "fun `initial state is stable`() = runTest(testDispatcher) {",
                "    val viewModel = createViewModel()",
                "    assertEquals(${analysis.primaryStateType}(), viewModel.$stateHolder.value)",
                "}",
            ),
        )
    }

    analysis.publicMethods.forEach { method ->
        setterPropertyName(method)?.let { propertyName ->
            methods += buildSetterTest(method, analysis, propertyName)
            return@forEach
        }

        if (isClearErrorMethod(method)) {
            buildClearErrorTest(method, analysis, successAction)?.let(methods::add)
            return@forEach
        }

        if (isActionMethod(method)) {
            buildValidationTest(method, analysis)?.let(methods::add)
            buildSuccessActionTest(method, analysis)?.let(methods::add)
        }
    }

    return methods.distinctBy { it.lines.joinToString("\n") }
}

private fun buildSetterTest(
    method: TargetMethod,
    analysis: ViewModelAnalysis,
    propertyName: String,
): RenderedTestMethod {
    val sampleInput = sampleInputForProperty(propertyName)
    val expected = transformInputByMethodBody(sampleInput, method.body.orEmpty())
    val stateHolder = analysis.primaryStateHolderName ?: "uiState"
    val assertions = mutableListOf<String>()
    assertions += "assertEquals(${quote(expected)}, viewModel.$stateHolder.value.$propertyName)"
    if ("errorMessage = null" in method.body.orEmpty()) {
        assertions += "assertNull(viewModel.$stateHolder.value.errorMessage)"
    }

    return RenderedTestMethod(
        lines = buildList {
            add("@Test")
            add("fun `${method.name} updates $propertyName in state`() = runTest(testDispatcher) {")
            add("    val viewModel = createViewModel()")
            add("    viewModel.${method.name}(${quote(sampleInput)})")
            assertions.forEach { add("    $it") }
            add("}")
        },
    )
}

private fun buildClearErrorTest(
    method: TargetMethod,
    analysis: ViewModelAnalysis,
    actionMethod: TargetMethod?,
): RenderedTestMethod? {
    val stateHolder = analysis.primaryStateHolderName ?: "uiState"
    val triggerMethod = actionMethod ?: return null

    return RenderedTestMethod(
        lines = listOf(
            "@Test",
            "fun `${method.name} clears an existing error`() = runTest(testDispatcher) {",
            "    val viewModel = createViewModel()",
            "    viewModel.${triggerMethod.name}()",
            "    assertNotNull(viewModel.$stateHolder.value.errorMessage)",
            "    viewModel.${method.name}()",
            "    assertNull(viewModel.$stateHolder.value.errorMessage)",
            "}",
        ),
    )
}

private fun buildValidationTest(
    method: TargetMethod,
    analysis: ViewModelAnalysis,
): RenderedTestMethod? {
    val validationMessage = extractErrorMessage(method.body.orEmpty()) ?: return null
    val stateHolder = analysis.primaryStateHolderName ?: "uiState"

    return RenderedTestMethod(
        lines = listOf(
            "@Test",
            "fun `${method.name} exposes validation error for invalid input`() = runTest(testDispatcher) {",
            "    val viewModel = createViewModel()",
            "    viewModel.${method.name}()",
            "    assertEquals(${quote(validationMessage)}, viewModel.$stateHolder.value.errorMessage)",
            "}",
        ),
    )
}

private fun buildSuccessActionTest(
    method: TargetMethod,
    analysis: ViewModelAnalysis,
): RenderedTestMethod? {
    val successProperty = extractSuccessStateProperty(method.body.orEmpty()) ?: return null
    val setterCalls = availableSetterMethods(analysis)
        .map { (methodName, propertyName) ->
            "    viewModel.$methodName(${quote(validInputForProperty(propertyName))})"
        }
        .ifEmpty { return null }
    val stateHolder = analysis.primaryStateHolderName ?: "uiState"

    return RenderedTestMethod(
        lines = buildList {
            add("@Test")
            add("fun `${method.name} updates success state when collaborators succeed`() = runTest(testDispatcher) {")
            add("    val viewModel = createViewModel()")
            addAll(setterCalls)
            add("    viewModel.${method.name}()")
            add("    advanceUntilIdle()")
            add("    assertTrue(viewModel.$stateHolder.value.$successProperty)")
            add("}")
        },
    )
}

private fun availableSetterMethods(analysis: ViewModelAnalysis): List<Pair<String, String>> {
    val fromAnalysis = analysis.publicMethods
        .mapNotNull { method ->
            val propertyName = setterPropertyName(method) ?: return@mapNotNull null
            method.name to propertyName
        }

    if (Files.exists(analysis.filePath)) {
        val sourceSetters = SETTER_SOURCE_PATTERN.findAll(Files.readString(analysis.filePath))
            .map { match ->
                val methodName = match.groupValues[1]
                val propertyName = match.groupValues[2].replaceFirstChar { it.lowercase(Locale.ROOT) }
                methodName to propertyName
            }
            .toList()
        return (fromAnalysis + sourceSetters).distinctBy { it.first }
    }

    return fromAnalysis
}

private fun setterPropertyName(method: TargetMethod): String? {
    val match = SETTER_METHOD_PATTERN.matchEntire(method.name) ?: return null
    return match.groupValues[1].replaceFirstChar { it.lowercase(Locale.ROOT) }
}

private fun isClearErrorMethod(method: TargetMethod): Boolean {
    return method.name.startsWith("clear") && "errorMessage = null" in method.body.orEmpty()
}

private fun isActionMethod(method: TargetMethod): Boolean {
    return method.name.startsWith("submit") ||
        method.name.startsWith("login") ||
        method.name.startsWith("save") ||
        method.name.startsWith("load")
}

private fun sampleInputForProperty(propertyName: String): String {
    return when (propertyName) {
        "email" -> " USER@Example.com "
        "password" -> "secretPass123"
        "fullName" -> " Ada Lovelace "
        "nickname" -> "  codexUser  "
        "bio" -> "Android engineer"
        else -> "sample-${propertyName.lowercase(Locale.ROOT)}"
    }
}

private fun validInputForProperty(propertyName: String): String {
    return when (propertyName) {
        "email" -> "user@example.com"
        "password" -> "secretPass123"
        "fullName" -> "Ada Lovelace"
        "nickname" -> "codexUser"
        "bio" -> "Android engineer"
        else -> "valid-${propertyName.lowercase(Locale.ROOT)}"
    }
}

private fun transformInputByMethodBody(input: String, body: String): String {
    var result = input
    if (".trim()" in body) {
        result = result.trim()
    }
    if (".lowercase()" in body) {
        result = result.lowercase(Locale.ROOT)
    }
    if (".uppercase()" in body) {
        result = result.uppercase(Locale.ROOT)
    }
    return result
}

private fun extractErrorMessage(body: String): String? {
    return ERROR_MESSAGE_PATTERN.find(body)?.groupValues?.get(1)
}

private fun extractSuccessStateProperty(body: String): String? {
    return SUCCESS_PROPERTY_PATTERN.findAll(body)
        .map { it.groupValues[1] }
        .firstOrNull { candidate ->
            candidate !in setOf("isLoading", "isSubmitting", "isSaving")
        }
}

private class GeneratedTestHelperBuilder(
    private val analysis: ViewModelAnalysis,
) {
    val declarations = mutableListOf<String>()
    val constructorArguments = mutableListOf<String>()
    val imports = mutableSetOf<String>()
    val warnings = mutableListOf<String>()
    private val generatedNames = mutableSetOf<String>()
    private val moduleRoot = inferModuleRootFromTarget(analysis.filePath)

    init {
        analysis.constructorDependencies.forEach { dependency ->
            constructorArguments += buildDependencyReference(
                preferredName = dependency.name,
                typeName = dependency.type,
                visited = mutableSetOf(analysis.className),
            )
        }
    }

    private fun buildDependencyReference(
        preferredName: String,
        typeName: String,
        visited: MutableSet<String>,
    ): String {
        if ("Dispatcher" in typeName) {
            return "$preferredName = testDispatcher"
        }

        if (typeName == "SavedStateHandle") {
            val sourceFile = resolveTypeFile(moduleRoot, typeName)
            if (sourceFile != null) {
                registerImport(Files.readString(sourceFile), typeName)
            }
            return "$preferredName = SavedStateHandle()"
        }

        if (!visited.add(typeName)) {
            warnings += "Detected recursive dependency while generating test doubles for $typeName."
            return "$preferredName = TODO(${quote("Provide a non-recursive test double for $typeName")})"
        }

        val sourceFile = resolveTypeFile(moduleRoot, typeName)
        if (sourceFile == null) {
            warnings += "Could not resolve source for $typeName. Generated test uses TODO() for this dependency."
            return "$preferredName = TODO(${quote("Provide a test double for $typeName")})"
        }

        val sourceText = Files.readString(sourceFile)
        registerImport(sourceText, typeName)
        return if (INTERFACE_PATTERN.containsMatchIn(sourceText)) {
            val variableName = ensureUniqueName(preferredName)
            declarations += buildInterfaceStub(variableName, typeName, sourceText)
            "$preferredName = $variableName"
        } else {
            val constructorParameters = parseConstructorParameters(sourceText, typeName)
            if (constructorParameters.isEmpty()) {
                warnings += "Could not infer constructor parameters for $typeName."
                "$preferredName = TODO(${quote("Provide a constructed instance of $typeName")})"
            } else {
                val nestedArguments = constructorParameters.map { nested ->
                    buildDependencyReference(
                        preferredName = nested.first,
                        typeName = nested.second,
                        visited = visited.toMutableSet(),
                    )
                }
                val variableName = ensureUniqueName(preferredName)
                val constructorCall = nestedArguments.joinToString(",\n        ") { argument ->
                    argument.substringAfter("= ").trim()
                }
                declarations += "private val $variableName = $typeName(\n        $constructorCall,\n    )"
                "$preferredName = $variableName"
            }
        }
    }

    private fun buildInterfaceStub(
        variableName: String,
        typeName: String,
        sourceText: String,
    ): String {
        val methods = INTERFACE_METHOD_PATTERN.findAll(sourceText).toList()
        val bodyLines = if (methods.isEmpty()) {
            listOf("    // TODO: define interface methods for $typeName")
        } else {
            methods.map { match ->
                val suspendModifier = match.groupValues[1]
                val methodName = match.groupValues[2]
                val parameters = match.groupValues[3].trim()
                val returnType = match.groupValues[4].ifBlank { "Unit" }.trim()
                val defaultValue = defaultReturnValue(returnType)
                "    override ${suspendModifier}fun $methodName($parameters): $returnType = $defaultValue"
            }
        }

        return buildString {
            appendLine("private val $variableName = object : $typeName {")
            bodyLines.forEach(::appendLine)
            append("}")
        }
    }

    private fun resolveTypeFile(moduleRoot: Path, typeName: String): Path? {
        val sourceRoots = listOf(
            moduleRoot.resolve("src/main/kotlin"),
            moduleRoot.resolve("src/main/java"),
        ).filter(Files::exists)

        sourceRoots.forEach { sourceRoot ->
            Files.walk(sourceRoot).use { paths ->
                val match = paths
                    .filter(Files::isRegularFile)
                    .filter { it.fileName.toString() == "$typeName.kt" }
                    .findFirst()
                if (match.isPresent) {
                    return match.get()
                }
            }
        }

        return null
    }

    private fun parseConstructorParameters(sourceText: String, typeName: String): List<Pair<String, String>> {
        val match = CLASS_WITH_CONSTRUCTOR_PATTERN.find(sourceText) ?: return emptyList()
        if (match.groupValues[1] != typeName) {
            return emptyList()
        }

        return splitParameters(match.groupValues[2])
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
                parts[0] to parts[1].removeSuffix(",")
            }
    }

    private fun registerImport(sourceText: String, typeName: String) {
        val packageName = PACKAGE_PATTERN.find(sourceText)?.groupValues?.get(1).orEmpty()
        if (packageName.isBlank() || packageName == analysis.packageName) {
            return
        }
        if ('.' in typeName) {
            return
        }
        imports += "import $packageName.$typeName"
    }

    private fun ensureUniqueName(name: String): String {
        var candidate = name.replaceFirstChar { it.lowercase(Locale.ROOT) }
        var index = 1
        while (!generatedNames.add(candidate)) {
            index += 1
            candidate = "$candidate$index"
        }
        return candidate
    }
}

private fun splitParameters(parameterBlock: String): List<String> {
    if (parameterBlock.isBlank()) {
        return emptyList()
    }

    val parameters = mutableListOf<String>()
    val builder = StringBuilder()
    var angleDepth = 0
    var parenDepth = 0

    parameterBlock.forEach { char ->
        when (char) {
            '<' -> angleDepth += 1
            '>' -> angleDepth = max(0, angleDepth - 1)
            '(' -> parenDepth += 1
            ')' -> parenDepth = max(0, parenDepth - 1)
            ',' -> {
                if (angleDepth == 0 && parenDepth == 0) {
                    parameters += builder.toString()
                    builder.clear()
                    return@forEach
                }
            }
        }

        builder.append(char)
    }

    if (builder.isNotBlank()) {
        parameters += builder.toString()
    }

    return parameters.map(String::trim).filter(String::isNotEmpty)
}

private fun defaultReturnValue(returnType: String): String {
    return when {
        returnType == "Unit" -> "Unit"
        returnType == "Boolean" -> "true"
        returnType == "Int" -> "0"
        returnType == "Long" -> "0L"
        returnType == "Float" -> "0f"
        returnType == "Double" -> "0.0"
        returnType == "String" -> quote("generated")
        returnType == "Result<Unit>" -> "Result.success(Unit)"
        returnType.startsWith("Result<String") -> "Result.success(${quote("generated-id")})"
        returnType.startsWith("Result<") -> "Result.success(${defaultValueForGeneric(returnType.removePrefix("Result<").removeSuffix(">"))})"
        returnType.startsWith("List<") -> "emptyList()"
        returnType.startsWith("Set<") -> "emptySet()"
        returnType.startsWith("Map<") -> "emptyMap()"
        returnType.endsWith("?") -> "null"
        else -> "TODO(${quote("Provide return value for $returnType")})"
    }
}

private fun defaultValueForGeneric(typeName: String): String {
    return when (typeName) {
        "Unit" -> "Unit"
        "String" -> quote("generated")
        "Boolean" -> "true"
        else -> when {
            typeName.startsWith("List<") -> "emptyList()"
            typeName.startsWith("Set<") -> "emptySet()"
            typeName.startsWith("Map<") -> "emptyMap()"
            else -> "TODO(${quote("Provide value for $typeName")})"
        }
    }
}

private fun quote(value: String): String {
    return buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}

private val SETTER_METHOD_PATTERN = Regex("""on([A-Z][A-Za-z0-9_]*)Changed""")
private val SETTER_SOURCE_PATTERN = Regex("""fun\s+(on([A-Z][A-Za-z0-9_]*)Changed)\s*\(""")
private val PACKAGE_PATTERN = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""", RegexOption.MULTILINE)
private val ERROR_MESSAGE_PATTERN = Regex("errorMessage\\s*=\\s*\"([^\"]+)\"")
private val SUCCESS_PROPERTY_PATTERN = Regex("""(is[A-Z][A-Za-z0-9_]*)\s*=\s*true""")
private val INTERFACE_PATTERN = Regex("""\binterface\s+[A-Za-z_][A-Za-z0-9_]*""")
private val INTERFACE_METHOD_PATTERN =
    Regex("""(?:override\s+)?(suspend\s+)?fun\s+([A-Za-z_][A-Za-z0-9_]*)\(([^)]*)\)\s*:\s*([A-Za-z0-9_.<>?]+)""")
private val CLASS_WITH_CONSTRUCTOR_PATTERN =
    Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)\s*\((.*?)\)\s*(?::|\{)""", setOf(RegexOption.DOT_MATCHES_ALL))
