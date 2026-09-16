package dev.studyflow.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention for modules that serialise JSON (issue #63).
 *
 * The compiler plugin and the runtime always travel together: a `@Serializable` class without the
 * plugin fails at runtime rather than at compile time, which is exactly the failure mode the API
 * contract work is meant to remove. Applying one plugin gets both.
 */
public class SerializationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

            dependencies {
                add("api", libs.findLibrary("kotlinx-serialization-json").get())
            }
        }
    }
}
