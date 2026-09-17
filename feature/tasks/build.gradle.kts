plugins {
    id("studyflow.android.feature")
    id("studyflow.screenshot")
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // The feature convention plugin wires `core:testing` into `testImplementation` only; the
    // screenshot previews need the same task fixtures to build believable list/detail states.
    add("screenshotTestImplementation", projects.core.testing)
}
