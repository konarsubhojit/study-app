plugins {
    id("studyflow.android.library")
    id("studyflow.compose")
}

dependencies {
    // The design system is the app's Compose surface: `api` so a feature that applies the theme
    // also sees Material 3, animation and the foundation layout types it themes.
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.animation)
    implementation(libs.androidx.core.ktx)
}
