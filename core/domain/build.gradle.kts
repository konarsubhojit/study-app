plugins {
    id("studyflow.jvm.library")
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)

    testImplementation(projects.core.testing)
}
