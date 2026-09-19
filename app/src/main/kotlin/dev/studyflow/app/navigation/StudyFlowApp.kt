package dev.studyflow.app.navigation

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.studyflow.core.designsystem.layout.StudyFlowScaffold
import dev.studyflow.core.designsystem.motion.StudyFlowMotion
import dev.studyflow.core.designsystem.theme.StudyFlowTheme
import dev.studyflow.feature.materials.MaterialDetailRoute
import dev.studyflow.feature.settings.NotificationSettingsRoute
import dev.studyflow.feature.tasks.TaskDetailRoute
import dev.studyflow.feature.tasks.TasksListRoute
import dev.studyflow.feature.history.HistoryRoute as HistoryScreenRoute
import dev.studyflow.feature.home.HomeRoute as HomeScreenRoute
import dev.studyflow.feature.insights.InsightsRoute as InsightsScreenRoute
import dev.studyflow.feature.insights.WeeklySummaryRoute as WeeklySummaryScreenRoute
import dev.studyflow.feature.materials.MaterialsRoute as MaterialsScreenRoute
import dev.studyflow.feature.timer.TimerRoute as TimerScreenRoute

@Composable
internal fun StudyFlowApp(
    initialRoute: AppRoute?,
    registerDeepLinkHandler: ((AppRoute) -> Unit) -> Unit,
) {
    StudyFlowTheme {
        val backStack = rememberNavBackStack(HomeRoute)
        val navigate: (AppRoute) -> Unit = { route ->
            if (backStack.lastOrNull() != route) backStack.add(route)
        }
        // The handler registration outlives a recomposition, so the effect reads the latest
        // callback instead of restarting whenever the caller passes a new lambda.
        val currentRegisterDeepLinkHandler by rememberUpdatedState(registerDeepLinkHandler)

        LaunchedEffect(initialRoute) {
            initialRoute?.let(navigate)
        }

        DisposableEffect(backStack) {
            currentRegisterDeepLinkHandler(navigate)
            onDispose { currentRegisterDeepLinkHandler({}) }
        }

        StudyFlowScaffold(
            bottomBar = {
                DestinationBar(
                    current = backStack.lastOrNull() as? AppRoute ?: HomeRoute,
                    onNavigate = navigate,
                )
            },
        ) { padding ->
            AppNavDisplay(
                backStack = backStack,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun AppNavDisplay(
    backStack: NavBackStack<NavKey>,
    modifier: Modifier = Modifier,
) {
    SharedTransitionLayout {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            entryProvider =
                entryProvider {
                    entry<HomeRoute> {
                        HomeScreenRoute(
                            onOpenTimer = { backStack.add(TimerRoute(openRunningTimer = true)) },
                            onOpenTask = { taskId -> backStack.add(TasksRoute(taskId)) },
                            onOpenMaterial = { materialId -> backStack.add(MaterialsRoute(materialId)) },
                            onOpenHistory = { backStack.add(HistoryRoute) },
                        )
                    }
                    entry<TimerRoute> { route ->
                        TimerScreenRoute(taskId = route.taskId, subjectId = route.subjectId)
                    }
                    entry<MaterialsRoute> { route ->
                        MaterialsEntry(route = route, backStack = backStack)
                    }
                    entry<TasksRoute> { route ->
                        TasksEntry(route = route, backStack = backStack)
                    }
                    entry<SettingsRoute> {
                        NotificationSettingsRoute()
                    }
                    entry<HistoryRoute> {
                        HistoryScreenRoute()
                    }
                    entry<InsightsRoute> {
                        InsightsScreenRoute(onOpenWeeklySummary = { backStack.add(WeeklySummaryRoute) })
                    }
                    entry<WeeklySummaryRoute> {
                        WeeklySummaryScreenRoute()
                    }
                },
            modifier = modifier,
            transitionSpec = {
                StudyFlowMotion.enter togetherWith StudyFlowMotion.exit
            },
            popTransitionSpec = {
                StudyFlowMotion.popEnter togetherWith StudyFlowMotion.popExit
            },
            predictivePopTransitionSpec = {
                StudyFlowMotion.popEnter togetherWith StudyFlowMotion.popExit
            },
            sharedTransitionScope = this,
        )
    }
}

@Composable
private fun TasksEntry(
    route: TasksRoute,
    backStack: NavBackStack<NavKey>,
) {
    if (route.taskId == null) {
        TasksListRoute(
            onTaskSelect = { taskId ->
                if (backStack.lastOrNull() != TasksRoute(taskId)) {
                    backStack.add(TasksRoute(taskId))
                }
            },
        )
    } else {
        TaskDetailRoute(
            taskId = route.taskId,
            onBack = { backStack.removeLastOrNull() },
            onStartStudySession = { taskId, subjectId ->
                backStack.add(TimerRoute(taskId = taskId, subjectId = subjectId))
            },
        )
    }
}

@Composable
private fun MaterialsEntry(
    route: MaterialsRoute,
    backStack: NavBackStack<NavKey>,
) {
    if (route.materialId == null) {
        MaterialsScreenRoute(
            onOpenMaterial = { materialId ->
                if (backStack.lastOrNull() != MaterialsRoute(materialId)) {
                    backStack.add(MaterialsRoute(materialId))
                }
            },
        )
    } else {
        MaterialDetailRoute(
            materialId = route.materialId,
            onBack = { backStack.removeLastOrNull() },
            onStartStudySession = { subjectId -> backStack.add(TimerRoute(subjectId = subjectId)) },
        )
    }
}

@Composable
private fun DestinationBar(
    current: AppRoute,
    onNavigate: (AppRoute) -> Unit,
) {
    NavigationBar {
        topLevelRoutes.forEach { route ->
            val label = route.label
            NavigationBarItem(
                selected = current.topLevelRoute == route,
                onClick = { onNavigate(route) },
                icon = { Text(label.take(1)) },
                label = { Text(label) },
            )
        }
    }
}

private val AppRoute.topLevelRoute: AppRoute
    get() =
        when (this) {
            HomeRoute -> HomeRoute
            is TimerRoute -> TimerRoute()
            is MaterialsRoute -> MaterialsRoute()
            is TasksRoute -> TasksRoute()
            HistoryRoute -> HistoryRoute
            InsightsRoute -> InsightsRoute
            // The weekly recap is a detail of the statistics it is computed from, so the bottom bar
            // keeps Insights selected while it is open.
            WeeklySummaryRoute -> InsightsRoute
            SettingsRoute -> SettingsRoute
        }

private val AppRoute.label: String
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
        }
