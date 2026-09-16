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
    implementation(libs.kotlin.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kotlinLibrary") {
            id = "studyflow.kotlin.library"
            implementationClass = "dev.studyflow.buildlogic.KotlinLibraryConventionPlugin"
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
