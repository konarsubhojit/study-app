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
    // Icons are a design decision, so features take the Material symbol set from the design system
    // rather than each declaring it (issue #167). R8 keeps only the symbols that are referenced.
    api(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
}
