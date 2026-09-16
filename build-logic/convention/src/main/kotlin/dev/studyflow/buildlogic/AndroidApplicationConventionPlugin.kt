package dev.studyflow.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention for the installable application module (issue #11).
 *
 * Only `:app` applies this. Everything the app shares with libraries lives in [configureAndroid],
 * so this plugin holds exactly what is specific to producing an APK.
 */
public class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("studyflow.hilt")
            pluginManager.apply("studyflow.quality")
            pluginManager.apply("studyflow.test")

            extensions.configure<ApplicationExtension> {
                configureAndroid(this)

                // The application ID is the identity Play Store and every installed device uses;
                // changing it after release orphans existing installs. It matches the namespace so
                // that a module declares it in neither place.
                defaultConfig.applicationId = namespace
                defaultConfig.targetSdk = libs.findVersion("targetSdk").get().requiredVersion.toInt()
                defaultConfig.buildConfigField("boolean", "CRASH_REPORTING_ENABLED", "false")
                defaultConfig.buildConfigField("boolean", "CRASH_REPORTING_OPTED_OUT", "true")

                buildFeatures.buildConfig = true

                buildTypes.getByName("release") {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                    // Module-specific keep rules are optional; R8 fails outright on a file that
                    // is listed but absent, which is a poor welcome for a new application module.
                    val moduleRules = file("proguard-rules.pro")
                    if (moduleRules.exists()) {
                        proguardFiles(moduleRules)
                    }
                }
            }

            dependencies {
                add("implementation", libs.findLibrary("androidx-core-ktx").get())
                add("implementation", libs.findLibrary("androidx-lifecycle-runtime-ktx").get())
            }

            // Only the module that owns an `Activity` needs the Compose entry point.
            pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
                dependencies {
                    add("implementation", libs.findLibrary("androidx-activity-compose").get())
                }
            }
        }
    }
}
