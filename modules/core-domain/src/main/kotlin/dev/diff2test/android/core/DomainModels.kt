package dev.diff2test.android.core

import java.nio.file.Path

enum class ChangeSource {
    GIT_DIFF,
    FILE_WATCHER,
}

enum class SymbolKind {
    CLASS,
    METHOD,
    PROPERTY,
    STATE,
}

enum class TestType {
    LOCAL_UNIT,
    INSTRUMENTED,
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

enum class ExecutionStatus {
    NOT_RUN,
    PASSED,
    FAILED,
}

data class ChangedSymbol(
    val name: String,
    val kind: SymbolKind,
    val signature: String? = null,
)

data class ChangedFile(
    val path: Path,
    val hunks: List<String> = emptyList(),
    val changedSymbols: List<ChangedSymbol> = emptyList(),
)

data class ChangeSet(
    val source: ChangeSource,
    val baseRef: String? = null,
    val headRef: String? = null,
    val files: List<ChangedFile> = emptyList(),
    val summary: String = "",
)

data class CollaboratorDependency(
    val name: String,
    val type: String,
    val role: String? = null,
)

data class TargetMethod(
    val name: String,
    val signature: String,
    val isPublic: Boolean = true,
    val isSuspend: Boolean = false,
    val mutatesState: Boolean = false,
    val body: String? = null,
)

data class ViewModelAnalysis(
    val className: String,
    val packageName: String = "",
    val filePath: Path,
    val constructorDependencies: List<CollaboratorDependency> = emptyList(),
    val publicMethods: List<TargetMethod> = emptyList(),
    val stateHolders: List<String> = emptyList(),
    val primaryStateHolderName: String? = null,
    val primaryStateType: String? = null,
    val androidFrameworkTouchpoints: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

data class StyleGuide(
    val mockLibrary: String = "MockK",
    val assertionStyle: String = "kotlin.test",
    val coroutineEntryPoint: String = "runTest",
    val flowProbe: String = "manual collection",
    val namingPattern: String = "given_when_then",
)

data class TestContext(
    val moduleName: String,
    val existingTestPatterns: List<String> = emptyList(),
    val styleGuide: StyleGuide = StyleGuide(),
    val collaboratorTypes: List<String> = emptyList(),
    val existingTestFiles: List<Path> = emptyList(),
)

data class TestScenario(
    val name: String,
    val goal: String,
    val expectedOutcome: String,
    val tags: Set<String> = emptySet(),
)

data class FakeSpec(
    val typeName: String,
    val strategy: String,
    val rationale: String,
)

data class AssertionSpec(
    val subject: String,
    val assertion: String,
    val expected: String,
)

data class TestPlan(
    val targetClass: String,
    val targetMethods: List<String>,
    val testType: TestType,
    val scenarios: List<TestScenario>,
    val requiredFakes: List<FakeSpec>,
    val assertions: List<AssertionSpec>,
    val riskLevel: RiskLevel,
    val notes: List<String> = emptyList(),
)

data class GeneratedFile(
    val relativePath: Path,
    val content: String,
)

data class GeneratedTestBundle(
    val plan: TestPlan,
    val files: List<GeneratedFile>,
    val warnings: List<String> = emptyList(),
)

data class GradleRunRequest(
    val module: String,
    val task: String,
    val testFilter: String? = null,
    val workingDirectory: Path,
)

data class ExecutionResult(
    val status: ExecutionStatus,
    val command: List<String>,
    val exitCode: Int = 0,
    val stdout: String = "",
    val stderr: String = "",
)

data class RepairAttempt(
    val attemptNumber: Int,
    val updatedFiles: List<GeneratedFile> = emptyList(),
    val summary: String,
    val applied: Boolean = false,
)

data class PolicyDecision(
    val shouldApplyPatch: Boolean,
    val shouldRetry: Boolean,
    val maxRetries: Int,
    val rationale: String,
)
