plugins {
    id("studyflow.android.library")
    id("studyflow.compose")
    id("studyflow.screenshot")
}

dependencies {
    api(projects.core.domain)
    // `MviViewModel` takes a `SavedStateHandle` in its public constructor, so subclasses in feature
    // modules see the type.
    api(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
}
