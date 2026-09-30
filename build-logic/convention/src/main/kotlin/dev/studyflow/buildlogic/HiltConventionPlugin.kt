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
                    // `@HiltWorker` is processed by androidx's compiler, not Dagger's. Without it
                    // no worker binding is generated, `HiltWorkerFactory` is handed an empty map
                    // and WorkManager falls back to a reflective `(Context, WorkerParameters)`
                    // constructor no `@AssistedInject` worker has — in every build type, silently.
                    // Applied to every Android Hilt module so a new worker anywhere is covered.
                    add("ksp", libs.findLibrary("androidx-hilt-compiler").get())
                }
            }
        }
    }
}
