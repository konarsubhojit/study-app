plugins {
    id("studyflow.android.library")
    id("studyflow.hilt")
}

dependencies {
    api(projects.core.domain)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.notifications)
    implementation(projects.core.datastore)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    // `UserSettings` is a protobuf-lite type the datastore module only exposes as `implementation`;
    // this module reads it directly to decide whether to post the digest summary.
    implementation(libs.protobuf.javalite)

    testImplementation(projects.core.testing)
}
