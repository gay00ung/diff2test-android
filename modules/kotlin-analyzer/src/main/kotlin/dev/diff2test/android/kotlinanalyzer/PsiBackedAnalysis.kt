package dev.diff2test.android.kotlinanalyzer

import dev.diff2test.android.core.ChangedFile
import dev.diff2test.android.core.CollaboratorDependency
import dev.diff2test.android.core.SymbolKind
import dev.diff2test.android.core.TargetMethod
import dev.diff2test.android.core.ViewModelAnalysis
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoots
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.configureJdkClasspathRoots
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingContext.EXPRESSION_TYPE_INFO
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.isError
import org.jetbrains.kotlin.utils.PathUtil

internal fun analyzeViewModelWithPsi(file: ChangedFile, resolvedPath: Path, sourceText: String): ViewModelAnalysis {
    val moduleRoot = inferModuleRoot(resolvedPath)
    val compilerResolution = CompilerResolutionCache.forModule(moduleRoot)
    val ktFile = compilerResolution?.findFile(resolvedPath)
        ?: KotlinPsiSupport.parse(resolvedPath, sourceText)
    val ktClass = findTargetClass(ktFile, resolvedPath)
    val resolutionContext = ResolutionContext(
        index = LocalSourceIndexCache.forModule(inferModuleRoot(resolvedPath)),
        imports = parseImports(ktFile),
        compiler = compilerResolution,
    )
    val allObservableHolders = parseObservableHolders(ktClass, resolutionContext)
    val observableHolders = allObservableHolders.filterNot { it.name.startsWith("_") }.ifEmpty { allObservableHolders }
    val changedMethodNames = file.changedSymbols
        .filter { it.kind == SymbolKind.METHOD }
        .map { it.name }
        .toSet()
    val publicMethods = parseMethods(ktClass, allObservableHolders)
    val methods = publicMethods
        .filter { changedMethodNames.isEmpty() || it.name in changedMethodNames }
        .ifEmpty { publicMethods.ifEmpty { fallbackMethods(file) } }

    val primaryStateHolder = observableHolders
        .firstOrNull { it.kind in PRIMARY_STATE_KINDS && !it.name.startsWith("_") }
        ?: observableHolders.firstOrNull { it.kind in PRIMARY_STATE_KINDS }

    return ViewModelAnalysis(
        className = ktClass.name ?: resolvedPath.fileName.toString().removeSuffix(".kt"),
        packageName = ktFile.packageFqName.asString(),
        filePath = resolvedPath,
        constructorDependencies = parseConstructorDependencies(ktClass, resolutionContext),
        publicMethods = methods,
        stateHolders = observableHolders.map { it.rendered },
        primaryStateHolderName = primaryStateHolder?.name,
        primaryStateType = primaryStateHolder?.typeName,
        androidFrameworkTouchpoints = detectAndroidFrameworkTouchpoints(ktClass, sourceText, resolutionContext),
        notes = listOf(
            resolutionContext.analysisNote(),
        ),
    )
}

internal data class ResolutionContext(
    val index: LocalSourceIndex,
    val imports: Map<String, String>,
    val compiler: CompilerResolutionSession? = null,
)

internal data class LocalSourceIndex(
    val aliasesBySimpleName: Map<String, TypeAliasDefinition>,
    val aliasesByQualifiedName: Map<String, TypeAliasDefinition>,
)

internal data class TypeAliasDefinition(
    val parameters: List<String>,
    val expandedType: String,
) {
    fun instantiate(arguments: List<String>): String? {
        if (parameters.isEmpty()) {
            return expandedType
        }
        if (arguments.size != parameters.size) {
            return null
        }

        var rendered = expandedType
        parameters.zip(arguments).forEach { (parameter, argument) ->
            rendered = rendered.replace(Regex("""\b${Regex.escape(parameter)}\b"""), argument)
        }
        return rendered
    }
}

private object LocalSourceIndexCache {
    private val cache = mutableMapOf<Path, LocalSourceIndex>()

    fun forModule(moduleRoot: Path): LocalSourceIndex {
        val normalized = moduleRoot.toAbsolutePath().normalize()
        return cache.getOrPut(normalized) {
            buildLocalSourceIndex(normalized)
        }
    }
}

private object KotlinPsiSupport {
    private val environment: KotlinCoreEnvironment by lazy {
        val configuration = CompilerConfiguration().apply {
            put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
            put(CommonConfigurationKeys.MODULE_NAME, "diff2test-android-kotlin-analyzer")
        }
        KotlinCoreEnvironment.createForProduction(
            Disposer.newDisposable("diff2test-android-kotlin-analyzer"),
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    }

    fun parse(path: Path, sourceText: String): KtFile {
        return KtPsiFactory(environment.project, markGenerated = false)
            .createFile(path.fileName.toString(), sourceText)
    }
}

internal data class CompilerResolutionSession(
    val bindingContext: BindingContext,
    val filesByPath: Map<Path, KtFile>,
    val hadErrors: Boolean,
) {
    fun findFile(path: Path): KtFile? = filesByPath[path.toAbsolutePath().normalize()]

    fun renderType(typeReference: KtTypeReference?): String? {
        val reference = typeReference ?: return null
        val resolved = bindingContext[BindingContext.TYPE, reference] ?: return null
        return resolved.renderCompilerType()
    }

    fun renderExpressionType(expression: KtExpression?): String? {
        val target = expression ?: return null
        val resolved = bindingContext.getType(target)
            ?: bindingContext[EXPRESSION_TYPE_INFO, target]?.type
        return resolved?.renderCompilerType()
    }
}

private object CompilerResolutionCache {
    private val cache = mutableMapOf<Path, CompilerResolutionSession?>()

    fun forModule(moduleRoot: Path): CompilerResolutionSession? {
        val normalized = moduleRoot.toAbsolutePath().normalize()
        return cache.getOrPut(normalized) { buildCompilerResolutionSession(normalized) }
    }
}

private fun findTargetClass(ktFile: KtFile, resolvedPath: Path): KtClass {
    val expectedName = resolvedPath.fileName.toString().removeSuffix(".kt")
    return ktFile.declarations
        .filterIsInstance<KtClass>()
        .firstOrNull { it.name == expectedName }
        ?: ktFile.declarations
            .filterIsInstance<KtClass>()
            .firstOrNull { it.name?.endsWith("ViewModel") == true }
        ?: error("No ViewModel class declaration found in $resolvedPath")
}

private fun parseConstructorDependencies(
    ktClass: KtClass,
    resolutionContext: ResolutionContext,
): List<CollaboratorDependency> {
    return ktClass.primaryConstructorParameters.mapNotNull { parameter ->
        val name = parameter.name ?: return@mapNotNull null
        val renderedType = resolutionContext.compiler?.renderType(parameter.typeReference)
        val typeText = renderedType
            ?: parameter.typeReference?.text?.trim()?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val type = resolveTypeText(typeText, resolutionContext)
        CollaboratorDependency(
            name = name,
            type = normalizeTypeName(type),
            role = inferDependencyRole(type),
        )
    }
}

private fun parseObservableHolders(
    ktClass: KtClass,
    resolutionContext: ResolutionContext,
): List<ObservableHolder> {
    return ktClass.declarations
        .filterIsInstance<KtProperty>()
        .mapNotNull { property ->
            val name = property.name ?: return@mapNotNull null
            val explicitType = resolutionContext.compiler?.renderType(property.typeReference)?.let { typeText ->
                parseObservableTypeText(resolveTypeText(typeText, resolutionContext))
            } ?: property.typeReference?.text?.let { typeText ->
                parseObservableTypeText(resolveTypeText(typeText, resolutionContext))
            }
            val inferredType = explicitType
                ?: parseObservableInitializer(property.initializer, resolutionContext)
            inferredType?.let { (kind, typeName) ->
                ObservableHolder(
                    name = name,
                    kind = kind,
                    typeName = normalizeTypeName(typeName ?: "*"),
                )
            }
        }
}

private fun parseObservableTypeText(typeText: String): Pair<String, String?>? {
    val match = OBSERVABLE_TYPE_PATTERN.matchEntire(typeText.replace(" ", "")) ?: return null
    val rawKind = match.groupValues[2]
    val rawType = match.groupValues.getOrNull(3)?.ifBlank { "*" }
    return rawKind to rawType
}

private fun parseObservableInitializer(initializer: KtExpression?, resolutionContext: ResolutionContext): Pair<String, String?>? {
    val callExpression = when (initializer) {
        is KtCallExpression -> initializer
        is KtDotQualifiedExpression -> initializer.selectorExpression as? KtCallExpression
        else -> null
    } ?: return null

    val calleeName = callExpression.calleeExpression?.text ?: return null
    if (calleeName !in setOf("MutableStateFlow", "MutableSharedFlow", "MutableLiveData")) {
        return null
    }

    val kind = calleeName.removePrefix("Mutable")
    val declaredType = callExpression.typeArgumentList
        ?.arguments
        ?.singleOrNull()
        ?.typeReference
        ?.text
        ?.trim()
    if (!declaredType.isNullOrBlank()) {
        return kind to declaredType
    }

    val firstArgument = callExpression.valueArguments.firstOrNull()?.getArgumentExpression()
    return kind to inferTypeFromExpression(firstArgument, resolutionContext)
}

private fun inferTypeFromExpression(expression: KtExpression?, resolutionContext: ResolutionContext): String? {
    resolutionContext.compiler?.renderExpressionType(expression)?.let(::normalizeTypeName)?.let { return it }
    return when (expression) {
        null -> null
        is KtCallExpression -> {
            expression.typeArgumentList?.arguments?.singleOrNull()?.typeReference?.text
                ?: expression.calleeExpression?.text?.takeIf(::looksLikeTypeName)
        }

        is KtNameReferenceExpression -> expression.getReferencedName().takeIf(::looksLikeTypeName)
        is KtDotQualifiedExpression -> inferTypeFromExpression(expression.selectorExpression, resolutionContext)
            ?: inferTypeFromExpression(expression.receiverExpression, resolutionContext)

        is KtStringTemplateExpression -> "String"
        else -> inferTypeFromText(expression.text)
    }?.let(::normalizeTypeName)
}

private fun inferTypeFromText(text: String): String? {
    val normalized = text.trim()
    return when {
        normalized.isBlank() -> null
        normalized == "true" || normalized == "false" -> "Boolean"
        normalized.matches(Regex("""-?\d+""")) -> "Int"
        normalized.matches(Regex("""-?\d+\.\d+""")) -> "Double"
        normalized.startsWith("\"") -> "String"
        looksLikeTypeName(normalized.substringBefore("(").substringBefore("<")) ->
            normalized.substringBefore("(").substringBefore("<")

        else -> null
    }
}

private fun looksLikeTypeName(candidate: String): Boolean {
    return candidate.isNotBlank() && candidate.first().isUpperCase()
}

private fun parseMethods(ktClass: KtClass, observableHolders: List<ObservableHolder>): List<TargetMethod> {
    val observableNames = observableHolders.map { it.name }.toSet()
    return ktClass.declarations
        .filterIsInstance<KtNamedFunction>()
        .filter(::isPublicFunction)
        .map { function ->
            val body = function.bodyExpression?.text.orEmpty()
            TargetMethod(
                name = function.name.orEmpty(),
                signature = renderFunctionSignature(function),
                isPublic = true,
                isSuspend = function.hasModifier(KtTokens.SUSPEND_KEYWORD),
                mutatesState = mutatesObservableState(body, observableNames),
                body = body,
            )
        }
}

private fun isPublicFunction(function: KtNamedFunction): Boolean {
    return !function.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
        !function.hasModifier(KtTokens.PROTECTED_KEYWORD) &&
        !function.hasModifier(KtTokens.INTERNAL_KEYWORD)
}

private fun renderFunctionSignature(function: KtNamedFunction): String {
    val suspendPrefix = if (function.hasModifier(KtTokens.SUSPEND_KEYWORD)) "suspend " else ""
    val parameters = function.valueParameters.joinToString(", ") { parameter ->
        val name = parameter.name ?: "_"
        val type = parameter.typeReference?.text?.trim()?.let { ": $it" }.orEmpty()
        "$name$type"
    }
    val returnType = function.typeReference?.text?.trim()?.let { ": $it" }.orEmpty()
    return "${suspendPrefix}fun ${function.name}($parameters)$returnType".trim()
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

private fun detectAndroidFrameworkTouchpoints(
    ktClass: KtClass,
    sourceText: String,
    resolutionContext: ResolutionContext,
): List<String> {
    val referencedTypes = buildSet {
        ktClass.superTypeListEntries.mapNotNullTo(this) { entry ->
            val resolved = resolutionContext.compiler?.renderType(entry.typeReference)
                ?: entry.typeReference?.text?.let { resolveTypeText(it, resolutionContext) }
            resolved?.substringBefore('<')?.trim()
        }
        ktClass.primaryConstructorParameters.mapNotNullTo(this) { parameter ->
            val resolved = resolutionContext.compiler?.renderType(parameter.typeReference)
                ?: parameter.typeReference?.text?.let { resolveTypeText(it, resolutionContext) }
            resolved?.substringBefore('<')?.trim()
        }
        ktClass.declarations.filterIsInstance<KtProperty>().mapNotNullTo(this) { property ->
            val resolved = resolutionContext.compiler?.renderType(property.typeReference)
                ?: property.typeReference?.text?.let { resolveTypeText(it, resolutionContext) }
            resolved?.substringBefore('<')?.trim()
        }
    }

    return ANDROID_TOUCHPOINTS.filter { it in referencedTypes || it in sourceText }
}

private fun parseImports(ktFile: KtFile): Map<String, String> {
    return ktFile.importDirectives.mapNotNull { directive ->
        val imported = directive.importPath?.pathStr ?: return@mapNotNull null
        val alias = directive.aliasName ?: imported.substringAfterLast('.')
        alias to imported
    }.toMap()
}

private fun resolveTypeText(typeText: String, resolutionContext: ResolutionContext): String {
    val normalized = typeText
        .replace("\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (normalized.isBlank()) {
        return normalized
    }

    val expanded = resolveAliasRecursively(normalized, resolutionContext)
    return TYPE_REFERENCE_PATTERN.replace(expanded) { match ->
        resolveSingleTypeReference(match.value, resolutionContext)
    }
}

private fun resolveAliasRecursively(typeText: String, resolutionContext: ResolutionContext): String {
    var current = typeText.trim()
    repeat(8) {
        val resolved = resolveAliasedWholeType(current, resolutionContext) ?: return current
        if (resolved == current) {
            return current
        }
        current = resolved
    }
    return current
}

private fun resolveAliasedWholeType(typeText: String, resolutionContext: ResolutionContext): String? {
    val nullableSuffix = if (typeText.endsWith("?")) "?" else ""
    val core = typeText.removeSuffix("?").trim()
    val invocation = parseTypeAliasInvocation(core)
    val direct = resolutionContext.index.aliasesByQualifiedName[core]
        ?: resolutionContext.index.aliasesBySimpleName[core]
        ?: resolutionContext.index.aliasesByQualifiedName[invocation.name]
        ?: resolutionContext.index.aliasesBySimpleName[invocation.name]
        ?: resolutionContext.imports[invocation.name]?.let { imported ->
            resolutionContext.index.aliasesByQualifiedName[imported]
                ?: resolutionContext.index.aliasesBySimpleName[imported.substringAfterLast('.')]
        }
        ?: return null

    val expanded = direct.instantiate(invocation.arguments) ?: return null
    return expanded.trim() + nullableSuffix
}

private fun resolveSingleTypeReference(candidate: String, resolutionContext: ResolutionContext): String {
    val first = candidate.firstOrNull()
    if (first == null || !first.isUpperCase()) {
        return candidate
    }

    val resolvedAlias = resolveAliasedWholeType(candidate, resolutionContext)
    if (resolvedAlias != null) {
        return resolveTypeText(resolvedAlias, resolutionContext)
    }

    val imported = resolutionContext.imports[candidate] ?: candidate
    return imported.substringAfterLast('.')
}

private fun inferModuleRoot(path: Path): Path {
    val normalized = path.toAbsolutePath().normalize()
    val srcIndex = (0 until normalized.nameCount).indexOfFirst { normalized.getName(it).toString() == "src" }
    if (srcIndex <= 0) {
        return normalized.parent ?: normalized
    }

    val moduleRoot = normalized.subpath(0, srcIndex)
    return normalized.root.resolve(moduleRoot)
}

private fun buildLocalSourceIndex(moduleRoot: Path): LocalSourceIndex {
    val aliasesBySimpleName = mutableMapOf<String, TypeAliasDefinition>()
    val aliasesByQualifiedName = mutableMapOf<String, TypeAliasDefinition>()
    val sourceRoots = listOf(
        moduleRoot.resolve("src/main/kotlin"),
        moduleRoot.resolve("src/main/java"),
    ).filter(Files::exists)

    sourceRoots.forEach { sourceRoot ->
        Files.walk(sourceRoot).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { it.fileName.toString().endsWith(".kt") }
                .forEach { file ->
                    val source = Files.readString(file)
                    val packageName = PACKAGE_PATTERN.find(source)?.groupValues?.getOrNull(1).orEmpty()
                    TYPE_ALIAS_PATTERN.findAll(source).forEach { match ->
                        val aliasName = match.groupValues[1]
                        val parameters = match.groupValues[2]
                            .split(',')
                            .map(String::trim)
                            .filter(String::isNotBlank)
                        val expanded = match.groupValues[3].trim()
                        val definition = TypeAliasDefinition(
                            parameters = parameters,
                            expandedType = expanded,
                        )
                        aliasesBySimpleName[aliasName] = definition
                        if (packageName.isNotBlank()) {
                            aliasesByQualifiedName["$packageName.$aliasName"] = definition
                        }
                    }
                }
        }
    }

    return LocalSourceIndex(
        aliasesBySimpleName = aliasesBySimpleName,
        aliasesByQualifiedName = aliasesByQualifiedName,
    )
}

private fun parseTypeAliasInvocation(typeText: String): TypeAliasInvocation {
    val trimmed = typeText.trim()
    val genericStart = trimmed.indexOf('<')
    if (genericStart < 0 || !trimmed.endsWith(">")) {
        return TypeAliasInvocation(trimmed, emptyList())
    }

    val name = trimmed.substring(0, genericStart).trim()
    val argumentBlock = trimmed.substring(genericStart + 1, trimmed.length - 1)
    return TypeAliasInvocation(name, splitTopLevelTypeArguments(argumentBlock))
}

private fun splitTopLevelTypeArguments(argumentBlock: String): List<String> {
    if (argumentBlock.isBlank()) {
        return emptyList()
    }

    val arguments = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0

    argumentBlock.forEach { character ->
        when (character) {
            '<' -> {
                depth += 1
                current.append(character)
            }
            '>' -> {
                depth = (depth - 1).coerceAtLeast(0)
                current.append(character)
            }
            ',' -> {
                if (depth == 0) {
                    arguments += current.toString().trim()
                    current.clear()
                } else {
                    current.append(character)
                }
            }
            else -> current.append(character)
        }
    }

    current.toString().trim().takeIf(String::isNotBlank)?.let(arguments::add)
    return arguments
}

private data class TypeAliasInvocation(
    val name: String,
    val arguments: List<String>,
)

private fun buildCompilerResolutionSession(moduleRoot: Path): CompilerResolutionSession? {
    val sourceRoots = listOf(
        moduleRoot.resolve("src/main/kotlin"),
        moduleRoot.resolve("src/main/java"),
    ).filter(Files::exists)
    if (sourceRoots.isEmpty()) {
        return null
    }

    val configuration = CompilerConfiguration()
    configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
    configuration.put(CommonConfigurationKeys.MODULE_NAME, "diff2test-android-kotlin-analyzer")
    configuration.put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_17)
    sourceRoots.forEach { configuration.addKotlinSourceRoot(it.toString()) }
    configuration.addJavaSourceRoots(sourceRoots.map(Path::toFile))
    configuration.configureJdkClasspathRoots()
    configuration.addJvmClasspathRoots(PathUtil.getJdkClassesRootsFromCurrentJre())
    currentJvmClasspathFiles().takeIf { it.isNotEmpty() }?.let { configuration.addJvmClasspathRoots(it) }

    val disposable = Disposer.newDisposable("diff2test-android-kotlin-analyzer-symbols")
    val environment = KotlinCoreEnvironment.createForProduction(
        disposable,
        configuration,
        EnvironmentConfigFiles.JVM_CONFIG_FILES,
    )
    val sourceFiles = environment.getSourceFiles()
    if (sourceFiles.isEmpty()) {
        return null
    }

    val analysisResult = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
        environment.project,
        sourceFiles,
        CliBindingTrace(),
        configuration,
        environment::createPackagePartProvider,
    )

    return CompilerResolutionSession(
        bindingContext = analysisResult.bindingContext,
        filesByPath = sourceFiles.associateBy(::normalizeKtFilePath),
        hadErrors = runCatching {
            analysisResult.throwIfError()
            false
        }.getOrElse { true },
    )
}

private fun currentJvmClasspathFiles(): List<File> {
    return System.getProperty("java.class.path")
        .orEmpty()
        .split(File.pathSeparator)
        .map(String::trim)
        .filter(String::isNotBlank)
        .map(::File)
        .filter(File::exists)
}

private fun normalizeKtFilePath(ktFile: KtFile): Path {
    val path = ktFile.virtualFilePath.takeIf(String::isNotBlank)
        ?: ktFile.name
    return Path.of(path).toAbsolutePath().normalize()
}

private fun KotlinType.renderCompilerType(): String? {
    if (this.isError) {
        return null
    }
    val rendered = DescriptorRenderer.SHORT_NAMES_IN_TYPES.renderType(this)
    val expanded = TYPE_ALIAS_EXPANSION_COMMENT.find(rendered)?.groupValues?.get(1) ?: rendered
    return normalizeTypeName(expanded)
}

private fun ResolutionContext.analysisNote(): String {
    val compilerSession = compiler
    if (compilerSession == null) {
        return "PSI-backed declaration analysis with local import and typealias resolution. Compiler-backed symbol resolution was unavailable for this module."
    }
    return if (compilerSession.hadErrors) {
        "Compiler-backed symbol resolution is enabled, with PSI fallback where module classpath symbols could not be fully resolved."
    } else {
        "Compiler-backed symbol resolution is enabled for same-module Kotlin sources."
    }
}

private val OBSERVABLE_TYPE_PATTERN = Regex("""^(Mutable)?(StateFlow|SharedFlow|LiveData|Flow|Channel)<(.+)>$""")
private val PACKAGE_PATTERN = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""", RegexOption.MULTILINE)
private val TYPE_ALIAS_PATTERN = Regex("""^\s*typealias\s+([A-Za-z_][A-Za-z0-9_]*)(?:<([^>]+)>)?\s*=\s*(.+)$""", RegexOption.MULTILINE)
private val TYPE_REFERENCE_PATTERN = Regex("""\b[A-Z][A-Za-z0-9_.]*\b""")
private val TYPE_ALIAS_EXPANSION_COMMENT = Regex("""/\*\s*=\s*(.*?)\s*\*/""")
