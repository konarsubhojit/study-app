plugins {
    id("studyflow.android.application")
}

dependencies {
    implementation(projects.feature.auth)
    implementation(projects.feature.insights)
    implementation(projects.feature.materials)
    implementation(projects.feature.settings)
    implementation(projects.feature.tasks)
    implementation(projects.feature.timer)
}
