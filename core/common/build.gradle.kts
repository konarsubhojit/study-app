plugins {
    id("studyflow.kotlin.library")
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
}
