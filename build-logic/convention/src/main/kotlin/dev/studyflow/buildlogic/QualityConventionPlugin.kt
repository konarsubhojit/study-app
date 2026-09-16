package dev.studyflow.buildlogic

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

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
        }
    }
}
