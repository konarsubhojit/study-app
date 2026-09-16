package dev.studyflow.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** The JDK version every module compiles against, read from the catalogue rather than hard-coded. */
internal val Project.javaVersion: String
    get() = libs.findVersion("javaToolchain").get().requiredVersion

/**
 * Kotlin options shared by JVM and Android modules (issue #11).
 *
 * `allWarningsAsErrors` is on everywhere: a warning that is allowed to persist is a warning nobody
 * reads. Explicit API mode is restricted to `:core:*`, whose published surface every other layer
 * compiles against; forcing it on UI modules would only add ceremony to `@Composable` functions.
 */
internal fun Project.configureKotlin() {
    extensions.configure<KotlinProjectExtension> {
        if (path.startsWith(":core:")) {
            explicitApi = ExplicitApiMode.Strict
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(javaVersion))
            allWarningsAsErrors.set(true)
        }
    }
}
