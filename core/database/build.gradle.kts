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
    implementation(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)
}
