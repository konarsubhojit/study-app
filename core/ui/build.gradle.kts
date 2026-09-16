plugins {
    id("studyflow.android.library")
    id("studyflow.compose")
}

dependencies {
    api(projects.core.domain)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
}
