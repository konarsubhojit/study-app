plugins {
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
    id("studyflow.module-boundaries")
}
