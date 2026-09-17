plugins {
    id("studyflow.android.feature")
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.notifications)
    implementation(projects.core.ui)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
