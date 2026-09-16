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
                add("implementation", libs.findLibrary("room-runtime").get())
                add("ksp", libs.findLibrary("room-compiler").get())
                add("testImplementation", libs.findLibrary("room-testing").get())
            }
        }
    }
}
