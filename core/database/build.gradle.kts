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
    // The task repository implements the `:core:domain` contract and maps rows to domain models.
    implementation(projects.core.domain)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(projects.core.testing)
}
