package dev.studyflow.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/**
 * Version catalogue accessor for convention plugins.
 *
 * Precompiled accessors (`libs.foo`) are not generated for plugin classes, so the catalogue is
 * resolved explicitly. Keeping this in one place means no convention plugin ever hard-codes a
 * version, which is the whole point of issue #12.
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
