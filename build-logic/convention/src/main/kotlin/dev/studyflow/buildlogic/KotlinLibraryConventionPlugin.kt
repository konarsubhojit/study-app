package dev.studyflow.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Convention for pure-Kotlin/JVM library modules.
 *
 * Applying this plugin is the entire build script of a `:core:*` module: toolchain, compiler
 * options, explicit API mode, test framework and quality gates all come from here (issue #11).
 *
 * These modules deliberately have no Android plugin applied. That is the mechanical enforcement
 * of "`:core:domain` and `:core:model` stay Android-free" — the Android SDK simply is not on the
 * compile classpath, so a stray `android.*` import fails to compile (issue #14).
 */
public class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("studyflow.quality")

            val javaVersion = libs.findVersion("javaToolchain").get().requiredVersion

            extensions.configure<JavaPluginExtension> {
                toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
            }

            extensions.configure<KotlinJvmProjectExtension> {
                explicitApi = ExplicitApiMode.Strict
                compilerOptions {
                    jvmTarget.set(JvmTarget.fromTarget(javaVersion))
                    allWarningsAsErrors.set(true)
                }
            }

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
        }
    }
}
