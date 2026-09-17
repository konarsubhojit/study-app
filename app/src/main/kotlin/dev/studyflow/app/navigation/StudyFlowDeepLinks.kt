package dev.studyflow.app.navigation

import android.net.Uri

internal enum class WidgetAction(
    internal val path: String,
) {
    HOME("home"),
    TIMER("timer"),
    MATERIALS("materials"),
    TASKS("tasks"),
    SETTINGS("settings"),
}

internal object StudyFlowDeepLinks {
    private const val SCHEME = "studyflow"
    private const val HOME = "home"
    private const val TIMER = "timer"
    private const val MATERIALS = "materials"
    private const val TASKS = "tasks"
    private const val SETTINGS = "settings"
    private const val WIDGET = "widget"
    private const val RUNNING = "running"

    internal fun uriFor(route: AppRoute): Uri =
        when (route) {
            HomeRoute -> uri(HOME)
            is TimerRoute -> uri(TIMER, RUNNING.takeIf { route.openRunningTimer })
            is MaterialsRoute -> uri(MATERIALS, route.materialId)
            is TasksRoute -> uri(TASKS, route.taskId)
            SettingsRoute -> uri(SETTINGS)
        }

    internal fun uriFor(action: WidgetAction): Uri = uri(WIDGET, action.path)

    internal fun routeForNewIntent(
        uri: Uri?,
        handledDeepLink: String?,
    ): AppRoute? =
        if (uri?.toString() == handledDeepLink) {
            null
        } else {
            routeFor(uri)
        }

    internal fun routeFor(uri: Uri?): AppRoute? {
        if (uri?.scheme != SCHEME) return null
        val segments = uri.pathSegments
        return when (uri.host) {
            HOME -> {
                HomeRoute.takeIf { segments.isEmpty() }
            }

            TIMER -> {
                when {
                    segments.isEmpty() -> TimerRoute()
                    segments == listOf(RUNNING) -> TimerRoute(openRunningTimer = true)
                    else -> null
                }
            }

            MATERIALS -> {
                identifier(segments)?.let(::MaterialsRoute) ?: MaterialsRoute().takeIf { segments.isEmpty() }
            }

            TASKS -> {
                identifier(segments)?.let(::TasksRoute) ?: TasksRoute().takeIf { segments.isEmpty() }
            }

            SETTINGS -> {
                SettingsRoute.takeIf { segments.isEmpty() }
            }

            WIDGET -> {
                widgetRoute(segments)
            }

            else -> {
                null
            }
        }
    }

    private fun uri(
        host: String,
        path: String? = null,
    ): Uri =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority(host)
            .apply { path?.let(::appendPath) }
            .build()

    private fun identifier(segments: List<String>): String? = segments.singleOrNull()?.takeIf(String::isNotBlank)

    private fun widgetRoute(segments: List<String>): AppRoute? =
        when (segments.singleOrNull()) {
            WidgetAction.HOME.path -> HomeRoute
            WidgetAction.TIMER.path -> TimerRoute(openRunningTimer = true)
            WidgetAction.MATERIALS.path -> MaterialsRoute()
            WidgetAction.TASKS.path -> TasksRoute()
            WidgetAction.SETTINGS.path -> SettingsRoute
            else -> null
        }
}
