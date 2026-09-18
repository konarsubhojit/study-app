plugins {
    id("studyflow.android.feature")
}

dependencies {
    implementation(projects.core.network)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    testImplementation(libs.kotlinx.coroutines.test)
}
