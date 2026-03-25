plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.modules.coreDomain)
    implementation(kotlin("compiler-embeddable"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
