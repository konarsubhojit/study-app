plugins {
    id("studyflow.android.feature")
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(projects.core.designsystem)
    implementation(projects.core.network)
    implementation(projects.core.ui)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    testImplementation(libs.kotlinx.coroutines.test)
}
