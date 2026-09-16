plugins {
    id("studyflow.android.application")
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.feature.auth)
    implementation(projects.feature.insights)
    implementation(projects.feature.materials)
    implementation(projects.feature.settings)
    implementation(projects.feature.tasks)
    implementation(projects.feature.timer)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
}
