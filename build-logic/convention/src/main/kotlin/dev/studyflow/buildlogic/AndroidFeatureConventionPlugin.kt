package dev.studyflow.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention for `:feature:*` modules (issue #11).
 *
 * A feature is an Android library with Compose UI and Hilt injection plus the dependencies every
 * feature needs. That is what makes a new feature's build file five lines: apply this, then list
 * only the dependencies that are genuinely specific to the feature.
 */
public class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("studyflow.android.library")
            pluginManager.apply("studyflow.compose")
            pluginManager.apply("studyflow.hilt")

            dependencies {
                add("implementation", libs.findLibrary("androidx-hilt-navigation-compose").get())
                add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
                add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())

                // The layers every feature is written against; a feature that needed none of them
                // would not be a feature. Cross-feature edges stay illegal (studyflow.module-boundaries).
                listOf(":core:model", ":core:common", ":core:domain").forEach { path ->
                    rootProject.findProject(path)?.let { add("implementation", it) }
                }
                rootProject.findProject(":core:testing")?.let { add("testImplementation", it) }
            }
        }
    }
}
