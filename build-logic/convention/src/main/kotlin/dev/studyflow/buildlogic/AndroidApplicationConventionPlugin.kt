package dev.studyflow.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** `-Pstudyflow.minifyRelease=false` disables R8 to isolate a shrinking-only crash; see CONTRIBUTING.md. */
private const val MINIFY_RELEASE_PROPERTY = "studyflow.minifyRelease"

/** `-Pstudyflow.shrinkReleaseResources=false` is the equivalent knob for resource shrinking. */
private const val SHRINK_RELEASE_RESOURCES_PROPERTY = "studyflow.shrinkReleaseResources"

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

            // Both default to `true`: the shipped release path is always fully shrunk. Flipping
            // either off is a debugging aid for telling an R8 keep-rule bug apart from an
            // unrelated crash, never a mode to ship (see "Diagnosing a release-only crash" in
            // CONTRIBUTING.md).
            val minifyRelease =
                providers.gradleProperty(MINIFY_RELEASE_PROPERTY).map(String::toBoolean).getOrElse(true)
            val shrinkReleaseResources =
                providers.gradleProperty(SHRINK_RELEASE_RESOURCES_PROPERTY).map(String::toBoolean).getOrElse(true)

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
                    isMinifyEnabled = minifyRelease
                    isShrinkResources = shrinkReleaseResources
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                    // Module-specific keep rules are optional; R8 fails outright on a file that
                    // is listed but absent, which is a poor welcome for a new application module.
                    val moduleRules = file("proguard-rules.pro")
                    if (moduleRules.exists()) {
                        proguardFiles(moduleRules)
                    }
                }

                // A minified, non-debuggable APK cannot be instrumented (the platform requires
                // `android:debuggable="true"` to attach a test runner) — see
                // "Testing the minified build" in CONTRIBUTING.md. `releaseTest` is `release`
                // (same `isMinifyEnabled`/`isShrinkResources`/keep rules) with only that flag
                // flipped, so the Gradle Managed Device instrumented suite exercises the exact
                // shrinking the shipped `release` build type gets, instead of only ever running
                // against `debug`, where R8 never runs at all.
                buildTypes.create("releaseTest") {
                    initWith(buildTypes.getByName("release"))
                    matchingFallbacks += "release"
                    isDebuggable = true
                    signingConfig = buildTypes.getByName("debug").signingConfig
                }
                testBuildType = "releaseTest"
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
