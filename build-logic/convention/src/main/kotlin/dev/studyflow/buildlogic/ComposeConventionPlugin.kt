package dev.studyflow.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/** Set `-Pstudyflow.composeCompilerReports=true` to have the compiler emit metrics and reports. */
private const val METRICS_PROPERTY = "studyflow.composeCompilerReports"

/**
 * Compose UI conventions: compiler plugin, the BOM-aligned dependency set, and on-demand
 * compiler metrics (issues #11 and #9).
 *
 * The metrics are opt-in because writing them defeats compilation avoidance. When performance work
 * needs them, `./gradlew assembleRelease -P$METRICS_PROPERTY=true` drops the stability and
 * recomposition reports under `build/compose-reports/`, with no build-file edit to review or revert.
 */
public class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            pluginManager.withPlugin("com.android.base") {
                extensions.configure(CommonExtension::class.java) {
                    buildFeatures.compose = true
                }
            }

            extensions.configure<ComposeCompilerGradlePluginExtension> {
                val reportsEnabled = providers.gradleProperty(METRICS_PROPERTY)
                    .map(String::toBoolean)
                    .getOrElse(false)
                if (reportsEnabled) {
                    metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
                    reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
                }
            }

            val bom = libs.findLibrary("androidx-compose-bom").get()
            dependencies {
                add("implementation", platform(bom))
                add("androidTestImplementation", platform(bom))
                add("implementation", libs.findLibrary("androidx-compose-ui").get())
                add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
                add("implementation", libs.findLibrary("androidx-compose-material3").get())
                add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
                add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
                add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
            }
        }
    }
}
