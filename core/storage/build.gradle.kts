plugins {
    id("studyflow.jvm.library")
}

dependencies {
    // The contract is opaque keys and expiring URLs, which needs nothing from the Android SDK:
    // keeping this module Android-free keeps the storage layer testable on the JVM (docs/adr/0002).
    // The part layout of a resumable upload is domain logic and already lives in `UploadPlanner`;
    // this module signs those parts rather than re-deriving them (issue #36).
    api(projects.core.domain)
    api(libs.ktor.client.core)
    // The storage Edge Function's bodies are a handful of fields read and written as JSON trees,
    // so the source works over any client, with or without content negotiation installed.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(projects.core.testing)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
