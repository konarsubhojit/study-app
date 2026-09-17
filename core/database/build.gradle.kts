import com.android.build.api.dsl.LibraryExtension

plugins {
    id("studyflow.android.library")
    id("studyflow.room")
}

extensions.configure<LibraryExtension> {
    // MigrationTestHelper reads compiler-exported historical schemas from the test APK's assets.
    sourceSets
        .getByName("test")
        .assets.directories
        .add("schemas")
}

dependencies {
    // The projection is derived by the domain's pure reducer, never stored as a second truth.
    api(projects.core.domain)
    implementation(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(projects.core.testing)
}
