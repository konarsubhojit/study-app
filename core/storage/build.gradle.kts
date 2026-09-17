plugins {
    id("studyflow.jvm.library")
    id("studyflow.hilt")
}

dependencies {
    // The contract is opaque keys and expiring URLs, which needs nothing from the Android SDK:
    // keeping this module Android-free keeps the storage layer testable on the JVM (docs/adr/0002).
    // The part layout of a resumable upload is domain logic and already lives in `UploadPlanner`;
    // this module signs those parts rather than re-deriving them (issue #36).
    api(projects.core.domain)
    api(libs.ktor.client.core)
    // `@Module`/`@InstallIn` without the Android runtime: the provider choice is wired here rather
    // than in `:app`, so swapping adapters touches no other module (issue #36).
    implementation(libs.hilt.core)

    testImplementation(projects.core.testing)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
