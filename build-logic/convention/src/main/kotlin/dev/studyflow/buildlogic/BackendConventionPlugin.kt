package dev.studyflow.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

public data class BackendConfiguration(
    val apiBaseUrl: String?,
    val storageBaseUrl: String?,
) {
    public val configured: Boolean get() = apiBaseUrl != null && storageBaseUrl != null
}

/** Resolve deployment configuration once; mock-only builds do not need a deployment. */
public class BackendConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val projectRef =
            target.providers
                .gradleProperty("studyflow.supabaseProjectRef")
                .orElse(target.providers.environmentVariable("STUDYFLOW_SUPABASE_PROJECT_REF"))
                .orNull
                ?.takeIf(String::isNotBlank)
                .also {
                    require(it == null || Regex("""[a-z0-9]{8,}""").matches(it)) {
                        "studyflow.supabaseProjectRef must be a Supabase project ref"
                    }
                }
        fun backendUrl(service: String): String? =
            target.providers
                .gradleProperty("studyflow.${service}BaseUrl")
                .orNull
                ?.takeIf(String::isNotBlank)
                ?: projectRef?.let { "https://$it.supabase.co/functions/v1/$service" }

        val configuration = BackendConfiguration(backendUrl("api"), backendUrl("storage"))
        val productionRequested =
            target.gradle.startParameter.taskNames.any { path ->
                val task = path.substringAfterLast(':')
                val targetsApp = !path.contains(':') || path.removePrefix(":").startsWith("app:")
                task.contains("production", ignoreCase = true) ||
                    (
                        targetsApp &&
                            !task.contains("mock", ignoreCase = true) &&
                            task !in TOOLING_TASKS &&
                            !task.startsWith("spotless") &&
                            !task.startsWith("detekt")
                    )
            }
        require(configuration.configured || !productionRequested) {
            "Production requires backend configuration. Supply -Pstudyflow.supabaseProjectRef=<project-ref> " +
                "or STUDYFLOW_SUPABASE_PROJECT_REF, or both -Pstudyflow.apiBaseUrl=<url> and " +
                "-Pstudyflow.storageBaseUrl=<url>."
        }
        target.extensions.add("backend", configuration)
    }

    private companion object {
        val TOOLING_TASKS = setOf(
            "help",
            "tasks",
            "projects",
            "properties",
            "dependencies",
            "dependencyInsight",
            "clean",
            "printVersionName",
            "checkModuleBoundaries",
            "checkModuleGraph",
            "generateModuleGraph",
        )
    }
}
