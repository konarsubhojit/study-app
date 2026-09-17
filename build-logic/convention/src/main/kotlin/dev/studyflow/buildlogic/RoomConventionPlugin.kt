package dev.studyflow.buildlogic

import androidx.room.gradle.RoomExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Room persistence wiring (issue #11).
 *
 * The exported schemas are committed: they are what makes a migration test able to open an old
 * database, and what turns an accidental schema change into a reviewable diff.
 */
public class RoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("androidx.room")
            pluginManager.apply("com.google.devtools.ksp")

            extensions.configure<RoomExtension> {
                schemaDirectory("$projectDir/schemas")
            }

            dependencies {
                // `api` rather than `implementation`: `StudyFlowDatabase` is itself a public
                // `RoomDatabase` subtype (issue #37 is the first consumer outside this module to
                // reference it directly), so a module that provides one needs Room's runtime on its
                // own compile classpath too.
                add("api", libs.findLibrary("room-runtime").get())
                add("ksp", libs.findLibrary("room-compiler").get())
                add("testImplementation", libs.findLibrary("room-testing").get())
            }
        }
    }
}
