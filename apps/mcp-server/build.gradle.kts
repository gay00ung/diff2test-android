plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.modules.coreDomain)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.diff2test.android.mcp.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

