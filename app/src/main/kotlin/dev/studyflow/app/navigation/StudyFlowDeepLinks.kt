package dev.studyflow.app.navigation

import android.net.Uri

internal enum class WidgetAction(
    internal val path: String,
) {
    HOME("home"),
    TIMER("timer"),
    MATERIALS("materials"),
    TASKS("tasks"),
    HISTORY("history"),
    INSIGHTS("insights"),
    SETTINGS("settings"),
}

internal object StudyFlowDeepLinks {
    private const val SCHEME = "studyflow"
    private const val HOME = "home"
    private const val TIMER = "timer"
    private const val MATERIALS = "materials"
    private const val TASKS = "tasks"
    private const val HISTORY = "history"
    private const val INSIGHTS = "insights"
    private const val SUMMARY = "summary"
    private const val SETTINGS = "settings"
    private const val PRIVACY = "privacy"
    private const val WIDGET = "widget"
    private const val RUNNING = "running"

    internal fun uriFor(route: AppRoute): Uri =
        when (route) {
            HomeRoute -> uri(HOME)

            is TimerRoute -> uri(TIMER, RUNNING.takeIf { route.openRunningTimer })

            is MaterialsRoute -> uri(MATERIALS, route.materialId)

            is TasksRoute -> uri(TASKS, route.taskId)

            HistoryRoute -> uri(HISTORY)

            InsightsRoute -> uri(INSIGHTS)

            WeeklySummaryRoute -> uri(SUMMARY)

            SettingsRoute -> uri(SETTINGS)

            // `studyflow://settings/privacy`: a deletion request has to be linkable from a help
            // page or a support reply, not only findable by scrolling.
            DataPrivacyRoute -> uri(SETTINGS, PRIVACY)
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
                timerRoute(segments)
            }

            MATERIALS -> {
                identifier(segments)?.let(::MaterialsRoute) ?: MaterialsRoute().takeIf { segments.isEmpty() }
            }

            TASKS -> {
                identifier(segments)?.let(::TasksRoute) ?: TasksRoute().takeIf { segments.isEmpty() }
            }

            HISTORY -> {
                HistoryRoute.takeIf { segments.isEmpty() }
            }

            INSIGHTS -> {
                InsightsRoute.takeIf { segments.isEmpty() }
            }

            SUMMARY -> {
                WeeklySummaryRoute.takeIf { segments.isEmpty() }
            }

            SETTINGS -> {
                settingsRoute(segments)
            }

            WIDGET -> {
                widgetRoute(segments)
            }

            else -> {
                null
            }
        }
    }

    private fun settingsRoute(segments: List<String>): AppRoute? =
        when {
            segments.isEmpty() -> SettingsRoute
            segments == listOf(PRIVACY) -> DataPrivacyRoute
            else -> null
        }

    private fun timerRoute(segments: List<String>): TimerRoute? =
        when {
            segments.isEmpty() -> TimerRoute()
            segments == listOf(RUNNING) -> TimerRoute(openRunningTimer = true)
            else -> null
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
            WidgetAction.HISTORY.path -> HistoryRoute
            WidgetAction.INSIGHTS.path -> InsightsRoute
            WidgetAction.SETTINGS.path -> SettingsRoute
            else -> null
        }
}
