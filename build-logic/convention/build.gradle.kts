plugins {
    `kotlin-dsl`
}

group = "dev.studyflow.buildlogic"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.javaToolchain.get())
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.room.gradlePlugin)
    compileOnly(libs.screenshot.gradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "studyflow.android.application"
            implementationClass = "dev.studyflow.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "studyflow.android.library"
            implementationClass = "dev.studyflow.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidFeature") {
            id = "studyflow.android.feature"
            implementationClass = "dev.studyflow.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("compose") {
            id = "studyflow.compose"
            implementationClass = "dev.studyflow.buildlogic.ComposeConventionPlugin"
        }
        register("hilt") {
            id = "studyflow.hilt"
            implementationClass = "dev.studyflow.buildlogic.HiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "studyflow.jvm.library"
            implementationClass = "dev.studyflow.buildlogic.JvmLibraryConventionPlugin"
        }
        register("screenshot") {
            id = "studyflow.screenshot"
            implementationClass = "dev.studyflow.buildlogic.ScreenshotConventionPlugin"
        }
        register("room") {
            id = "studyflow.room"
            implementationClass = "dev.studyflow.buildlogic.RoomConventionPlugin"
        }
        register("test") {
            id = "studyflow.test"
            implementationClass = "dev.studyflow.buildlogic.TestConventionPlugin"
        }
        register("quality") {
            id = "studyflow.quality"
            implementationClass = "dev.studyflow.buildlogic.QualityConventionPlugin"
        }
        register("moduleBoundaries") {
            id = "studyflow.module-boundaries"
            implementationClass = "dev.studyflow.buildlogic.ModuleBoundariesConventionPlugin"
        }
    }
}
