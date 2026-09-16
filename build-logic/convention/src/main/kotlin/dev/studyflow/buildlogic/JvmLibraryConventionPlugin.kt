package dev.studyflow.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure

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
public class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("studyflow.quality")
            pluginManager.apply("studyflow.test")

            extensions.configure<JavaPluginExtension> {
                toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
            }

            configureKotlin()
        }
    }
}
