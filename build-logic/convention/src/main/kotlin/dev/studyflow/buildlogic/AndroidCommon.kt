package dev.studyflow.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project

/**
 * Configuration every Android module shares, whatever it produces (issue #11).
 *
 * SDK levels come from the catalogue, so a platform bump is a one-line change rather than a sweep
 * through every module. The namespace is derived from the module path, which keeps a new module's
 * build file free of boilerplate that only ever restates where the module lives; a module that
 * needs a different namespace can still set one.
 *
 * The packaging excludes drop the duplicated licence files that otherwise make two libraries with
 * the same metadata fail to merge into an APK.
 */
internal fun Project.configureAndroid(extension: CommonExtension) {
    with(extension) {
        namespace = defaultNamespace()
        compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()

        defaultConfig.minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
        defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        compileOptions.sourceCompatibility = JavaVersion.toVersion(javaVersion)
        compileOptions.targetCompatibility = JavaVersion.toVersion(javaVersion)

        packaging.resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
            "/META-INF/*.version",
        )
    }

    configureKotlin()
}

/** `:feature:timer` becomes `dev.studyflow.feature.timer`. */
private fun Project.defaultNamespace(): String =
    path.split(":")
        .filter { it.isNotEmpty() }
        .joinToString(separator = ".", prefix = "dev.studyflow.") { segment ->
            segment.replace("-", "").lowercase()
        }
