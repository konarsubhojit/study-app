plugins {
    id("studyflow.jvm.library")
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    // The network fakes answer over the real client, so a feature test exercises production
    // serialization, retries and error mapping rather than a stand-in (issue #63).
    api(projects.core.network)
    api(libs.ktor.client.mock)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
}
