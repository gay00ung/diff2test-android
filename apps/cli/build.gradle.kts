plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.modules.coreDomain)
    implementation(projects.modules.changeDetector)
    implementation(projects.modules.kotlinAnalyzer)
    implementation(projects.modules.contextBuilder)
    implementation(projects.modules.testClassifier)
    implementation(projects.modules.testPlanner)
    implementation(projects.modules.testGenerator)
    implementation(projects.modules.gradleRunner)
    implementation(projects.modules.styleIndex)
    implementation(projects.modules.policyEngine)
    implementation("org.tomlj:tomlj:1.1.1")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.diff2test.android.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
