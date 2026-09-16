plugins {
    id("studyflow.jvm.library")
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    // The fakes live in `main` so every module can use them, which means the test framework they
    // extend — JUnit tags, extensions — has to be a production dependency of this module.
    api(libs.junit.jupiter)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
}
