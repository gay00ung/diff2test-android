plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.modules.coreDomain)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

