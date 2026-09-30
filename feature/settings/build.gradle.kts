plugins {
    id("studyflow.android.feature")
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.notifications)
    implementation(projects.core.ui)
    // The alarm ringtone picker persists its choice in UserSettings (issue #48), via
    // `AlarmRingtoneSettings` — a narrow read/write interface `:core:datastore` exposes so this
    // module never needs the generated protobuf `UserSettings` type on its own classpath.
    implementation(projects.core.datastore)
    implementation(libs.androidx.activity.compose)
    // The log export writes through MediaStore and, before Android 10, shares through
    // `FileProvider`; both come from androidx.core, which this module therefore names itself.
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
