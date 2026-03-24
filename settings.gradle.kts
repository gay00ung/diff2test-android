rootProject.name = "diff2test-android"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":apps:cli",
    ":apps:mcp-server",
    ":modules:core-domain",
    ":modules:change-detector",
    ":modules:kotlin-analyzer",
    ":modules:context-builder",
    ":modules:test-classifier",
    ":modules:test-planner",
    ":modules:test-generator",
    ":modules:test-repair",
    ":modules:gradle-runner",
    ":modules:style-index",
    ":modules:policy-engine",
)
