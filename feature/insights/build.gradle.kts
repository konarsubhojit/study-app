plugins {
    id("studyflow.android.feature")
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
