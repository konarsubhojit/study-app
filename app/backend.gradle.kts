val projectRef =
    providers
        .gradleProperty("studyflow.supabaseProjectRef")
        .orElse(providers.environmentVariable("STUDYFLOW_SUPABASE_PROJECT_REF"))
        .orNull
        ?.takeIf(String::isNotBlank)
        .also {
            require(it == null || Regex("""[a-z0-9]{8,}""").matches(it)) {
                "studyflow.supabaseProjectRef must be a Supabase project ref"
            }
        }

fun backendUrl(service: String): String? =
    providers
        .gradleProperty("studyflow.${service}BaseUrl")
        .orNull
        ?.takeIf(String::isNotBlank)
        ?: projectRef?.let { "https://$it.supabase.co/functions/v1/$service" }

val apiUrl = backendUrl("api")
val storageUrl = backendUrl("storage")
val configured = apiUrl != null && storageUrl != null
val productionRequested =
    gradle.startParameter.taskNames.any { path ->
        val task = path.substringAfterLast(':')
        val targetsApp = !path.contains(':') || path.startsWith(":app:")
        task.contains("production", ignoreCase = true) ||
            (
                targetsApp &&
                    !task.contains("mock", ignoreCase = true) &&
                    task !in
                    setOf(
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
                    ) &&
                    !task.startsWith("spotless") &&
                    !task.startsWith("detekt")
            )
    }
require(configured || !productionRequested) {
    "Production requires backend configuration. Supply -Pstudyflow.supabaseProjectRef=<project-ref> " +
        "or STUDYFLOW_SUPABASE_PROJECT_REF, or both -Pstudyflow.apiBaseUrl=<url> and " +
        "-Pstudyflow.storageBaseUrl=<url>."
}

extra["backendConfigured"] = configured
extra["apiBaseUrl"] = apiUrl.orEmpty()
extra["storageBaseUrl"] = storageUrl.orEmpty()
