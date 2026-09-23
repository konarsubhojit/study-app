plugins {
    id("studyflow.jvm.library")
    // The export/import archive format is JSON the domain owns (issue #78): the schema, its
    // version and the merge rules belong beside the business rules, not in a platform module.
    id("studyflow.serialization")
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
