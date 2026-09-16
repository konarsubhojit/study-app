package dev.studyflow.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/**
 * Architectural layering rules, enforced by the build rather than by code review (issue #14).
 *
 * The plan's module map only works if the arrows point one way. These rules turn an illegal
 * dependency into a build failure with an explanatory message, so the layering cannot rot
 * silently as the project grows.
 */
internal object ModuleBoundaryRules {
    /** Modules that must not depend on any other project — the leaves of the graph. */
    private val leafModules = setOf(":core:model")

    /** The only modules the pure domain layer may build on. */
    private val domainAllowedDependencies = setOf(":core:model", ":core:common")

    /** Test-only support module; any module may use it from its test source set. */
    private const val TESTING_MODULE = ":core:testing"

    /**
     * Validates the project dependency graph.
     *
     * Production edges carry the full layering rules. Test edges are checked more loosely — test
     * code is allowed to reach for [TESTING_MODULE] — but may still not smuggle in a cross-feature
     * dependency.
     *
     * @param productionEdges module path to project paths it depends on at compile/runtime.
     * @param testEdges module path to project paths its test source sets depend on.
     * @return human-readable violations; empty when the graph is legal.
     */
    fun violations(
        productionEdges: Map<String, Set<String>>,
        testEdges: Map<String, Set<String>>,
    ): List<String> = buildList {
        productionEdges.forEachEdge { module, dependency ->
            productionViolation(module, dependency)?.let(::add)
        }
        testEdges.forEachEdge { module, dependency ->
            testViolation(module, dependency)?.let(::add)
        }
    }

    private inline fun Map<String, Set<String>>.forEachEdge(action: (String, String) -> Unit) {
        entries.sortedBy { it.key }.forEach { (module, dependencies) ->
            dependencies.sorted().forEach { dependency -> action(module, dependency) }
        }
    }

    private fun productionViolation(module: String, dependency: String): String? =
        sharedViolation(module, dependency)
            ?: when {
                module in leafModules ->
                    "$module -> $dependency: $module must stay dependency-free so every layer " +
                        "(and a future KMP target) can share it."

                module == ":core:domain" && dependency !in domainAllowedDependencies ->
                    "$module -> $dependency: the domain layer may only depend on " +
                        "${domainAllowedDependencies.joinToString()}."

                else -> null
            }

    private fun testViolation(module: String, dependency: String): String? =
        if (dependency == TESTING_MODULE) null else sharedViolation(module, dependency)

    private fun sharedViolation(module: String, dependency: String): String? = when {
        module.startsWith(":feature:") && dependency.startsWith(":feature:") ->
            "$module -> $dependency: feature modules must not depend on each other. " +
                "Move the shared code into a :core module."

        module.startsWith(":core:") && dependency.startsWith(":feature:") ->
            "$module -> $dependency: core modules must not depend on feature modules."

        module.startsWith(":core:") && dependency == ":app" ->
            "$module -> $dependency: core modules must not depend on the application module."

        else -> null
    }
}

/** Fails the build when the module dependency graph breaks the layering rules. */
public abstract class CheckModuleBoundariesTask : DefaultTask() {
    @get:Input
    public abstract val productionDependencies: MapProperty<String, Set<String>>

    @get:Input
    public abstract val testDependencies: MapProperty<String, Set<String>>

    @TaskAction
    public fun check() {
        val violations = ModuleBoundaryRules.violations(
            productionEdges = productionDependencies.get(),
            testEdges = testDependencies.get(),
        )
        if (violations.isNotEmpty()) {
            error(
                buildString {
                    appendLine("Module dependency boundaries violated:")
                    violations.forEach { appendLine("  - $it") }
                    appendLine()
                    append("See docs/adr/0002-architecture-layering.md for the rules.")
                },
            )
        }
    }
}

/** Registers [CheckModuleBoundariesTask] on the root project and wires it into `check`. */
public class ModuleBoundariesConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) {
            "studyflow.module-boundaries must be applied to the root project"
        }
        // `base` gives the root project the lifecycle `check` task this plugin hooks into.
        target.pluginManager.apply("base")

        val checkBoundaries = target.tasks.register<CheckModuleBoundariesTask>("checkModuleBoundaries") {
            group = "verification"
            description = "Fails when the project dependency graph breaks the architectural layering."
        }

        target.gradle.projectsEvaluated {
            val production = target.subprojects.associate { it.path to it.projectDependencyPaths(test = false) }
            val test = target.subprojects.associate { it.path to it.projectDependencyPaths(test = true) }
            checkBoundaries.configure {
                productionDependencies.set(production)
                testDependencies.set(test)
            }
        }

        target.tasks.named("check") { dependsOn(checkBoundaries) }
    }

    private fun Project.projectDependencyPaths(test: Boolean): Set<String> =
        configurations
            .filter { it.isDeclarable() && it.name.startsWith("test") == test }
            .flatMapTo(mutableSetOf()) { configuration ->
                configuration.dependencies.filterIsInstance<ProjectDependency>().map { it.path }
            }

    /** Only the buckets a build script declares dependencies in carry intent worth checking. */
    private fun Configuration.isDeclarable(): Boolean =
        name in DECLARABLE_CONFIGURATIONS ||
            name.removePrefix("test").replaceFirstChar(Char::lowercaseChar) in DECLARABLE_CONFIGURATIONS

    private companion object {
        val DECLARABLE_CONFIGURATIONS = setOf("api", "implementation", "compileOnly", "runtimeOnly")
    }
}
