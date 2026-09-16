package dev.studyflow.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Hilt dependency injection wiring (issue #11).
 *
 * KSP rather than kapt: Hilt's annotation processors run natively against the Kotlin AST, which
 * removes the stub-generation round trip kapt needs. Applying the Gradle plugin (rather than only
 * the processor) is what installs the bytecode transform `@AndroidEntryPoint` relies on.
 */
public class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")

            dependencies {
                add("ksp", libs.findLibrary("hilt-compiler").get())
            }

            pluginManager.withPlugin("com.android.base") {
                pluginManager.apply("com.google.dagger.hilt.android")
                dependencies {
                    add("implementation", libs.findLibrary("hilt-android").get())
                }
            }
        }
    }
}
