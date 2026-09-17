plugins {
    id("studyflow.jvm.library")
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    // Plain JVM artifact (no Android dependency): lets the history repository contract expose a
    // `PagingSource` without pulling Android into a module that must stay unit-testable and
    // KMP-ready (see settings.gradle.kts).
    api(libs.androidx.paging.common)

    testImplementation(projects.core.testing)
}
