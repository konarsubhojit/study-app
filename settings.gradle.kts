pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "studyflow"

// `projects.core.model` instead of `project(":core:model")` — a typo becomes a compile error.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Pure-Kotlin core. These modules must never depend on the Android SDK so that the
// domain logic stays testable on the JVM and remains KMP-ready (see docs/adr/0002).
include(":core:model")
include(":core:common")
include(":core:domain")
include(":core:testing")
