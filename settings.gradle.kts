pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        gradlePluginPortal()
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

rootProject.name = "studyflow"

// `projects.core.model` instead of `project(":core:model")` — a typo becomes a compile error.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Pure-Kotlin core. These modules must never depend on the Android SDK so that the
// domain logic stays testable on the JVM and remains KMP-ready (see docs/adr/0002).
include(":core:model")
include(":core:common")
include(":core:domain")
include(":core:designsystem")
include(":core:ui")
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":core:storage")
include(":core:notifications")
include(":core:scheduling")
include(":core:testing")

include(":feature:timer")
include(":feature:materials")
include(":feature:tasks")
include(":feature:insights")
include(":feature:settings")
include(":feature:auth")
include(":feature:history")
include(":benchmark")

// The installable application. Adding a module needs a line here and nothing else (issue #11).
include(":app")
