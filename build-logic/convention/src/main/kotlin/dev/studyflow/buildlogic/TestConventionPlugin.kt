package dev.studyflow.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

/**
 * JUnit tag carried by `dev.studyflow.core.testing.quarantine.Flaky`.
 *
 * Duplicated as a literal because `:build-logic` is a separate build and cannot depend on
 * `:core:testing`; the annotation's own test pins the tag on its side, so changing it there without
 * changing it here silently stops quarantining. Change both or neither.
 */
private const val FLAKY_TAG = "flaky"

/** `-Pstudyflow.quarantine=true` flips a test task from "skip the flakes" to "run only the flakes". */
private const val QUARANTINE_PROPERTY = "studyflow.quarantine"

/** AGP's own JaCoCo report over the debug unit tests, created by `enableUnitTestCoverage`. */
private const val ANDROID_UNIT_TEST_COVERAGE_TASK = "createDebugUnitTestCoverageReport"

/**
 * Test conventions shared by JVM and Android modules (issues #11 and #65).
 *
 * JUnit 5 is the single test framework, so a test reads the same wherever it lives. Android modules
 * additionally get `includeAndroidResources`, which is what lets a unit test read resources, and
 * Robolectric for the logic that genuinely needs a platform implementation rather than a stub.
 *
 * Two policies are enforced here rather than left to discipline:
 *
 * - **Quarantine.** Tests tagged [FLAKY_TAG] never run in the default suite, so a known flake can
 *   neither block a pull request nor teach the team that a red build is nothing to worry about.
 *   `-P[QUARANTINE_PROPERTY]` inverts the filter, which is how the nightly slow suite keeps
 *   watching them. An empty quarantine is the goal, so the inverted run tolerates finding nothing.
 * - **Coverage.** Reports fall out of `check` in XML, so a pull request can be annotated with the
 *   coverage of the lines it changed. Coverage is a signal, not a target — see CONTRIBUTING.md.
 */
public class TestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val quarantineOnly = providers.gradleProperty(QUARANTINE_PROPERTY)
                .map(String::toBoolean)
                .orElse(false)

            tasks.withType<Test>().configureEach {
                val runQuarantinedTests = quarantineOnly.get()
                useJUnitPlatform {
                    if (runQuarantinedTests) includeTags(FLAKY_TAG) else excludeTags(FLAKY_TAG)
                }
                if (runQuarantinedTests) {
                    failOnNoDiscoveredTests.set(false)
                }
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

            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                configureJvmCoverage()
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
                    // Robolectric only speaks JUnit 4, so the vintage engine runs it on the same
                    // JUnit Platform as everything else instead of splitting the suite in two.
                    add("testImplementation", libs.findLibrary("robolectric").get())
                    add("testImplementation", libs.findLibrary("junit4").get())
                    add("testRuntimeOnly", libs.findLibrary("junit-vintage-engine").get())
                    add("androidTestImplementation", libs.findLibrary("androidx-test-junit").get())
                    add("androidTestImplementation", libs.findLibrary("androidx-test-espresso-core").get())
                }

                // AGP builds the report task itself once `enableUnitTestCoverage` is on; hooking it
                // into `check` is what puts Android modules into the same coverage report CI reads.
                // It is an error for that task to find no coverage data, so it only joins `check`
                // for a module that has unit tests, and never when the run is filtered down to the
                // quarantine, which is empty by design.
                if (file("src/test").isDirectory && !quarantineOnly.get()) {
                    tasks.named("check") {
                        dependsOn(tasks.matching { it.name == ANDROID_UNIT_TEST_COVERAGE_TASK })
                    }
                }
            }
        }
    }
}

/**
 * JaCoCo for the JVM modules, where the business rules live.
 *
 * The report depends on the test run and not the other way round, so `./gradlew test` — the inner
 * loop — stays a plain test run, while `check` also leaves an XML report behind for CI to read.
 * Android modules get the equivalent from AGP's own unit test coverage, enabled in
 * `configureAndroid` and wired into `check` above.
 */
private fun Project.configureJvmCoverage() {
    pluginManager.apply("jacoco")

    extensions.configure(JacocoPluginExtension::class.java) {
        toolVersion = libs.findVersion("jacoco").get().requiredVersion
    }

    tasks.withType<JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    tasks.named("check") {
        dependsOn(tasks.withType<JacocoReport>())
    }
}
