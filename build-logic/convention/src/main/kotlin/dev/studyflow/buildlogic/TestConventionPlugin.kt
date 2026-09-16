package dev.studyflow.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Test conventions shared by JVM and Android modules (issue #11).
 *
 * JUnit 5 is the single test framework, so a test reads the same wherever it lives. Android modules
 * additionally get `includeAndroidResources`, which is what lets a Robolectric-free unit test read
 * resources instead of being promoted to an instrumentation test.
 */
public class TestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
                testLogging {
                    events("failed")
                    exceptionFormat = TestExceptionFormat.FULL
                }
            }

            dependencies {
                add("testImplementation", libs.findLibrary("junit-jupiter").get())
                add("testImplementation", libs.findLibrary("junit-jupiter-params").get())
                add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
            }

            pluginManager.withPlugin("com.android.base") {
                extensions.configure(CommonExtension::class.java) {
                    testOptions.unitTests.isIncludeAndroidResources = true
                    testOptions.unitTests.isReturnDefaultValues = true
                }

                // An Android unit test compilation always contains generated sources (BuildConfig,
                // Hilt, Room), so Gradle's "sources present but no tests found" guard fires on a
                // module that simply has no unit tests yet.
                tasks.withType<Test>().configureEach {
                    failOnNoDiscoveredTests.set(false)
                }

                dependencies {
                    add("androidTestImplementation", libs.findLibrary("androidx-test-junit").get())
                    add("androidTestImplementation", libs.findLibrary("androidx-test-espresso-core").get())
                }
            }
        }
    }
}
