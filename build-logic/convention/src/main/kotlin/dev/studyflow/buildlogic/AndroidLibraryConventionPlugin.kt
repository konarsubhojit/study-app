package dev.studyflow.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Convention for Android library modules — `:core:*` and `:feature:*` code that needs the
 * platform (issue #11).
 *
 * A module that does not need the Android SDK uses `studyflow.jvm.library` instead; keeping the two
 * apart is what stops the domain layer drifting onto the platform (docs/adr/0002).
 *
 * The Kotlin plugin is not applied here: from AGP 9 the Android plugins compile Kotlin themselves.
 */
public class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("studyflow.quality")
            pluginManager.apply("studyflow.test")

            extensions.configure<LibraryExtension> {
                configureAndroid(this)
            }
        }
    }
}
