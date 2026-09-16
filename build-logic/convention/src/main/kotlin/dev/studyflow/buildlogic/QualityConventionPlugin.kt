package dev.studyflow.buildlogic

import com.android.build.api.variant.LintLifecycleExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import java.io.File

/** Lint findings that already existed may be parked here; new ones must be fixed (issue #13). */
private const val LINT_BASELINE_FILE = "lint-baseline.xml"

/**
 * Formatting and static analysis gates (issue #13).
 *
 * Wired into `check` by the plugins themselves, so `./gradlew check` is the single entry point
 * that CI runs. Style is enforced by tooling rather than by review comments.
 */
public class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.gitlab.arturbosch.detekt")
            pluginManager.apply("com.diffplug.spotless")

            val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion
            val detektConfigFile = rootProject.layout.projectDirectory.file("config/detekt/detekt.yml")

            extensions.configure<SpotlessExtension> {
                kotlin {
                    target("src/**/*.kt")
                    ktlint(ktlintVersion)
                    trimTrailingWhitespace()
                    endWithNewline()
                }
                kotlinGradle {
                    target("*.gradle.kts")
                    ktlint(ktlintVersion)
                    trimTrailingWhitespace()
                    endWithNewline()
                }
            }

            extensions.configure<DetektExtension> {
                buildUponDefaultConfig = true
                allRules = false
                parallel = true
                config.setFrom(detektConfigFile)
                basePath = rootProject.projectDir.absolutePath
            }

            tasks.withType<Detekt>().configureEach {
                jvmTarget = libs.findVersion("javaToolchain").get().requiredVersion
                reports {
                    html.required.set(true)
                    xml.required.set(true)
                    sarif.required.set(true)
                    txt.required.set(false)
                    md.required.set(false)
                }
            }

            // Compose has its own failure modes — unstable parameters, state hoisting, modifier
            // order — that the generic Kotlin rules cannot see, so the ruleset is added exactly to
            // the modules that compile Compose code.
            pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
                dependencies {
                    add("detektPlugins", libs.findLibrary("compose-detekt-rules").get())
                }
            }

            configureAndroidLint()
        }
    }
}

/**
 * Android Lint gate for every module that has the Android plugin (issue #13).
 *
 * `warningsAsErrors` is what makes Lint a gate rather than a report nobody opens. From AGP 9 the
 * `lint` block is no longer on the module extension, so the DSL is reached through the lint
 * lifecycle extension instead.
 *
 * The baseline is only honoured when the file is actually checked in: a missing baseline must not
 * be generated silently, because a generated baseline is how new findings disappear. Only the
 * application module checks its dependencies — it is the one place that sees the merged manifest
 * and resource set, so elsewhere it would only repeat findings the library already reported.
 * See CONTRIBUTING.md.
 */
private fun Project.configureAndroidLint() {
    pluginManager.withPlugin("com.android.base") {
        val checkDependencies = pluginManager.hasPlugin("com.android.application")
        val checkedInBaseline = file(LINT_BASELINE_FILE).takeIf(File::exists)

        extensions.configure(LintLifecycleExtension::class.java) {
            finalizeDsl { lint ->
                lint.warningsAsErrors = true
                lint.abortOnError = true
                lint.checkTestSources = true
                // Generated sources are not something a reviewer can act on.
                lint.checkGeneratedSources = false
                lint.checkDependencies = checkDependencies
                lint.sarifReport = true
                lint.htmlReport = true
                lint.xmlReport = true
                lint.baseline = checkedInBaseline
            }
        }
    }
}
