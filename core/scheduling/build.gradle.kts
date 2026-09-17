plugins {
    id("studyflow.android.library")
    id("studyflow.hilt")
}

dependencies {
    api(projects.core.domain)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.notifications)
    // The reminder delivery worker/receiver load the task (and its subject) they were scheduled
    // for, and persist completion/snooze/session state directly — the same repositories a feature
    // module would use, wired here because nothing else in the app binds them yet (issue #47).
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    // `StudyFlowDatabase` and `UserSettings` are Room/protobuf-lite types the database and
    // datastore modules only expose as `implementation`; this module reads them directly (rather
    // than only through their repository interfaces) to build the Hilt bindings those modules do
    // not provide yet, so it needs the same runtime on its own compile classpath.
    implementation(libs.room.runtime)
    implementation(libs.protobuf.javalite)

    testImplementation(projects.core.testing)
}
