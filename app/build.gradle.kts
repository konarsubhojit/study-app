plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.studyflow.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.studyflow.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
    }

    compileOptions {
        val javaVersion = JavaVersion.toVersion(libs.versions.javaToolchain.get())
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    lint {
        sarifReport = true
    }
}
