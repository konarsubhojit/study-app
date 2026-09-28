package dev.studyflow.app.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface AppRoute : NavKey

@Serializable
internal data object HomeRoute : AppRoute

@Serializable
internal data class TimerRoute(
    val openRunningTimer: Boolean = false,
    val taskId: String? = null,
    val subjectId: String? = null,
) : AppRoute

@Serializable
internal data class MaterialsRoute(
    val materialId: String? = null,
) : AppRoute

@Serializable
internal data class TasksRoute(
    val taskId: String? = null,
) : AppRoute

@Serializable
internal data object SettingsRoute : AppRoute

@Serializable
internal data object HistoryRoute : AppRoute

@Serializable
internal data object InsightsRoute : AppRoute

/** Export, restore and account deletion (issue #78); reached from settings, never a tab of its own. */
@Serializable
internal data object DataPrivacyRoute : AppRoute

/** The weekly recap the summary notification opens (issue #63); reachable from insights too. */
@Serializable
internal data object WeeklySummaryRoute : AppRoute

/**
 * The destinations the navigation surface offers (issue #167).
 *
 * Material 3 specifies three to five destinations for a navigation bar. History is reached from
 * Insights — it is the same session data, read one session at a time — and settings from the home
 * app bar, so neither costs a slot in the bar.
 */
internal val topLevelRoutes: List<AppRoute> =
    listOf(HomeRoute, TimerRoute(), MaterialsRoute(), TasksRoute(), InsightsRoute)

/**
 * The bar destination a route belongs to, or `null` when it belongs to none and nothing in the bar
 * should read as selected.
 */
internal val AppRoute.topLevelRoute: AppRoute?
    get() =
        when (this) {
            HomeRoute -> HomeRoute

            is TimerRoute -> TimerRoute()

            is MaterialsRoute -> MaterialsRoute()

            is TasksRoute -> TasksRoute()

            // History and the weekly recap are both readings of the statistics Insights computes,
            // so the bar keeps Insights selected while either is open.
            HistoryRoute -> InsightsRoute

            InsightsRoute -> InsightsRoute

            WeeklySummaryRoute -> InsightsRoute

            // Settings, and the data & privacy screen reached from it, are opened from an app-bar
            // action rather than the bar.
            SettingsRoute, DataPrivacyRoute -> null
        }

/** The label shown for a destination in the navigation surface. */
internal val AppRoute.label: String
    get() =
        when (this) {
            HomeRoute -> "Home"
            is TimerRoute -> "Timer"
            is MaterialsRoute -> "Materials"
            is TasksRoute -> "Tasks"
            HistoryRoute -> "History"
            InsightsRoute -> "Insights"
            WeeklySummaryRoute -> "Weekly summary"
            SettingsRoute -> "Settings"
            DataPrivacyRoute -> "Data & privacy"
        }
