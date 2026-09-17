plugins {
    id("studyflow.android.library")
}

dependencies {
    // `NotificationPostResult` is reported through `AppLogger`, so callers see the logging facade.
    api(projects.core.common)
    implementation(libs.androidx.core.ktx)

    testImplementation(projects.core.testing)
}
