plugins {
    id("studyflow.jvm.library")
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
}
