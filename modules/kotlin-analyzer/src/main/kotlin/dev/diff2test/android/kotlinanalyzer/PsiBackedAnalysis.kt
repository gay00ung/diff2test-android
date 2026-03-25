package dev.diff2test.android.kotlinanalyzer

import dev.diff2test.android.core.ChangedFile
import dev.diff2test.android.core.CollaboratorDependency
import dev.diff2test.android.core.SymbolKind
import dev.diff2test.android.core.TargetMethod
import dev.diff2test.android.core.ViewModelAnalysis
import java.nio.file.Path
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
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

internal fun analyzeViewModelWithPsi(file: ChangedFile, resolvedPath: Path, sourceText: String): ViewModelAnalysis {
    val ktFile = KotlinPsiSupport.parse(resolvedPath, sourceText)
    val ktClass = findTargetClass(ktFile, resolvedPath)
    val allObservableHolders = parseObservableHolders(ktClass)
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
        constructorDependencies = parseConstructorDependencies(ktClass),
        publicMethods = methods,
        stateHolders = observableHolders.map { it.rendered },
        primaryStateHolderName = primaryStateHolder?.name,
        primaryStateType = primaryStateHolder?.typeName,
        androidFrameworkTouchpoints = detectAndroidFrameworkTouchpoints(ktClass, sourceText),
        notes = listOf(
            "PSI-backed declaration analysis without symbol resolution. Covers constructors, observable holders, and changed public methods.",
        ),
    )
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

private fun parseConstructorDependencies(ktClass: KtClass): List<CollaboratorDependency> {
    return ktClass.primaryConstructorParameters.mapNotNull { parameter ->
        val name = parameter.name ?: return@mapNotNull null
        val type = parameter.typeReference?.text?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        CollaboratorDependency(
            name = name,
            type = normalizeTypeName(type),
            role = inferDependencyRole(type),
        )
    }
}

private fun parseObservableHolders(ktClass: KtClass): List<ObservableHolder> {
    return ktClass.declarations
        .filterIsInstance<KtProperty>()
        .mapNotNull { property ->
            val name = property.name ?: return@mapNotNull null
            val explicitType = property.typeReference?.text?.let(::parseObservableTypeText)
            val inferredType = explicitType ?: parseObservableInitializer(property.initializer)
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

private fun parseObservableInitializer(initializer: KtExpression?): Pair<String, String?>? {
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
    return kind to inferTypeFromExpression(firstArgument)
}

private fun inferTypeFromExpression(expression: KtExpression?): String? {
    return when (expression) {
        null -> null
        is KtCallExpression -> {
            expression.typeArgumentList?.arguments?.singleOrNull()?.typeReference?.text
                ?: expression.calleeExpression?.text?.takeIf(::looksLikeTypeName)
        }

        is KtNameReferenceExpression -> expression.getReferencedName().takeIf(::looksLikeTypeName)
        is KtDotQualifiedExpression -> inferTypeFromExpression(expression.selectorExpression)
            ?: inferTypeFromExpression(expression.receiverExpression)

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

private fun detectAndroidFrameworkTouchpoints(ktClass: KtClass, sourceText: String): List<String> {
    val referencedTypes = buildSet {
        ktClass.superTypeListEntries.mapNotNullTo(this) { entry -> entry.typeReference?.text?.substringBefore('<')?.trim() }
        ktClass.primaryConstructorParameters.mapNotNullTo(this) { parameter ->
            parameter.typeReference?.text?.substringBefore('<')?.trim()
        }
        ktClass.declarations.filterIsInstance<KtProperty>().mapNotNullTo(this) { property ->
            property.typeReference?.text?.substringBefore('<')?.trim()
        }
    }

    return ANDROID_TOUCHPOINTS.filter { it in referencedTypes || it in sourceText }
}

private val OBSERVABLE_TYPE_PATTERN = Regex("""^(Mutable)?(StateFlow|SharedFlow|LiveData|Flow|Channel)<(.+)>$""")
