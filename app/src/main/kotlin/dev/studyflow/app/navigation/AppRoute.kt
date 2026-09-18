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

internal val topLevelRoutes: List<AppRoute> =
    listOf(HomeRoute, TimerRoute(), MaterialsRoute(), TasksRoute(), HistoryRoute, SettingsRoute)
