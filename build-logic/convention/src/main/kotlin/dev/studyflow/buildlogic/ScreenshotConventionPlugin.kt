package dev.studyflow.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Compose preview screenshot tests (issue #17).
 *
 * `@Preview` functions in `src/screenshotTest` are rendered by layoutlib on the JVM and compared
 * against the images checked in under `src/screenshotTestDebug/reference`, so a theme or layout
 * regression fails a build rather than reaching a device:
 *
 * ```
 * ./gradlew :core:designsystem:validateDebugScreenshotTest   # compare (runs as part of `check`)
 * ./gradlew :core:designsystem:updateDebugScreenshotTest     # re-record after an intended change
 * ```
 *
 * The plugin is opt-in per module because recording references costs repository space; applying it
 * only where a screenshot actually proves something keeps that cost honest.
 */
public class ScreenshotConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.compose.screenshot")

            extensions.configure(CommonExtension::class.java) {
                @Suppress("UnstableApiUsage")
                experimentalProperties["android.experimental.enableScreenshotTest"] = true
            }

            dependencies {
                // Rendering needs the tooling artifact, not just the preview annotations.
                add("screenshotTestImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
                add("screenshotTestImplementation", libs.findLibrary("screenshot-validation-api").get())
            }

            tasks.named("check") {
                dependsOn("validateDebugScreenshotTest")
            }
        }
    }
}
