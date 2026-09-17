plugins {
    id("studyflow.jvm.library")
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    // Repository fakes implement the `:core:domain` contracts they stand in for.
    api(projects.core.domain)
    // The network fakes answer over the real client, so a feature test exercises production
    // serialization, retries and error mapping rather than a stand-in (issue #63).
    api(projects.core.network)
    api(libs.ktor.client.mock)
    // `FakeSessionHistoryRepository` implements `SessionHistoryRepository`'s `PagingSource`-typed
    // history query, the same reason `:core:domain` itself depends on this artifact.
    api(libs.androidx.paging.common)
    // The fakes live in `main` so every module can use them, which means the test framework they
    // extend — JUnit tags, extensions — has to be a production dependency of this module.
    api(libs.junit.jupiter)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
}
