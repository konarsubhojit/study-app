plugins {
    id("studyflow.android.library")
}

dependencies {
    api(projects.core.domain)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(projects.core.testing)
}
