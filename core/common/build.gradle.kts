plugins {
    id("studyflow.jvm.library")
}

dependencies {
    api(projects.core.model)
    api(libs.javax.inject)
    api(libs.kotlinx.coroutines.core)
}
