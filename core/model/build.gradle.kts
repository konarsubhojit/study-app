plugins {
    id("studyflow.jvm.library")
}

dependencies {
    // kotlinx-datetime rather than java.time: the domain model must stay KMP-ready, and its
    // time-zone rules are consistent across platforms (see docs/adr/0002).
    api(libs.kotlinx.datetime)
}
