import com.android.build.api.dsl.LibraryExtension

plugins {
    id("studyflow.android.library")
    id("studyflow.room")
    id("studyflow.hilt")
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
    // The task repository implements the `:core:domain` contract and maps rows to domain models.
    implementation(projects.core.domain)
    implementation(libs.androidx.paging.common)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.room.paging)

    testImplementation(projects.core.testing)
}
