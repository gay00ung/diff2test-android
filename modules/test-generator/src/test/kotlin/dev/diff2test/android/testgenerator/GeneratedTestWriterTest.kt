package dev.diff2test.android.testgenerator

import dev.diff2test.android.core.GeneratedFile
import dev.diff2test.android.core.GeneratedTestBundle
import dev.diff2test.android.core.RiskLevel
import dev.diff2test.android.core.StyleGuide
import dev.diff2test.android.core.TargetMethod
import dev.diff2test.android.core.TestContext
import dev.diff2test.android.core.TestPlan
import dev.diff2test.android.core.TestType
import dev.diff2test.android.core.ViewModelAnalysis
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedTestWriterTest {
    @Test
    fun `writes generated files under requested output root`() {
        val root = Files.createTempDirectory("d2t-generated-tests")
        val writer = FileSystemGeneratedTestWriter()
        val bundle = GeneratedTestBundle(
            plan = TestPlan(
                targetClass = "SignUpViewModel",
                targetMethods = listOf("submitRegistration"),
                testType = TestType.LOCAL_UNIT,
                scenarios = emptyList(),
                requiredFakes = emptyList(),
                assertions = emptyList(),
                riskLevel = RiskLevel.LOW,
            ),
            files = listOf(
                GeneratedFile(
                    relativePath = Path.of("src/test/kotlin/com/example/auth/SignUpViewModelTest.kt"),
                    content = "package com.example.auth",
                ),
            ),
        )

        val writtenFiles = writer.write(bundle, root)

        assertEquals(1, writtenFiles.size)
        assertTrue(Files.exists(writtenFiles.first()))
        assertEquals("package com.example.auth", Files.readString(writtenFiles.first()))
    }

    @Test
    fun `infers module root from source path`() {
        val source = Path.of("fixtures/sample-app/app/src/main/java/com/example/auth/SignUpViewModel.kt")

        val moduleRoot = inferModuleRootFromTarget(source)

        assertEquals(Path.of("fixtures/sample-app/app"), moduleRoot)
    }

    @Test
    fun `generator emits meaningful assertions for setter and action methods`() {
        val generator = KotlinUnitTestGenerator()
        val analysis = ViewModelAnalysis(
            className = "SignUpViewModel",
            packageName = "com.example.auth",
            filePath = Path.of("fixtures/sample-app/app/src/main/java/com/example/auth/SignUpViewModel.kt"),
            publicMethods = listOf(
                TargetMethod(
                    name = "onEmailChanged",
                    signature = "fun onEmailChanged(value: String)",
                    mutatesState = true,
                    body = """
                        fun onEmailChanged(value: String) {
                            _uiState.update { current ->
                                current.copy(email = value.trim().lowercase(), errorMessage = null)
                            }
                        }
                    """.trimIndent(),
                ),
                TargetMethod(
                    name = "submitRegistration",
                    signature = "fun submitRegistration()",
                    mutatesState = true,
                    body = """
                        fun submitRegistration() {
                            if (snapshot.email.isBlank()) {
                                _uiState.update { current ->
                                    current.copy(errorMessage = "Please enter a valid email and a password with at least 8 characters")
                                }
                                return
                            }
                            registerUser(...).onSuccess {
                                _uiState.update { current -> current.copy(isRegistered = true) }
                            }
                        }
                    """.trimIndent(),
                ),
            ),
            stateHolders = listOf("uiState: StateFlow<SignUpUiState>"),
            primaryStateHolderName = "uiState",
            primaryStateType = "SignUpUiState",
        )
        val plan = TestPlan(
            targetClass = "SignUpViewModel",
            targetMethods = listOf("onEmailChanged", "submitRegistration"),
            testType = TestType.LOCAL_UNIT,
            scenarios = emptyList(),
            requiredFakes = emptyList(),
            assertions = emptyList(),
            riskLevel = RiskLevel.LOW,
        )
        val context = TestContext(
            moduleName = "app",
            styleGuide = StyleGuide(),
        )

        val bundle = generator.generate(plan, context, analysis)
        val generatedFile = bundle.files.single()
        val content = generatedFile.content

        assertEquals(
            Path.of("src/test/kotlin/com/example/auth/SignUpViewModelGeneratedTest.kt"),
            generatedFile.relativePath,
        )

        assertTrue("assertEquals(SignUpUiState(), viewModel.uiState.value)" in content)
        assertTrue("class SignUpViewModelGeneratedTest" in content)
        assertTrue("viewModel.onEmailChanged(\" USER@Example.com \")" in content)
        assertTrue("assertEquals(\"user@example.com\", viewModel.uiState.value.email)" in content)
        assertTrue("viewModel.submitRegistration()" in content)
        assertTrue("assertEquals(\"Please enter a valid email and a password with at least 8 characters\", viewModel.uiState.value.errorMessage)" in content)
        assertTrue("assertTrue(viewModel.uiState.value.isRegistered)" in content)
    }
}
