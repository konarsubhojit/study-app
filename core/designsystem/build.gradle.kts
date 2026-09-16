plugins {
    id("studyflow.android.library")
    id("studyflow.compose")
    id("studyflow.screenshot")
}

dependencies {
    // The design system is the app's Compose surface: `api` so a module that applies the theme also
    // sees the Material 3 and animation types it themes.
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.animation)
    implementation(libs.androidx.core.ktx)
}
