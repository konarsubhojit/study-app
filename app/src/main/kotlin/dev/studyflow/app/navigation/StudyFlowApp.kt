package dev.studyflow.app.navigation

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.studyflow.core.designsystem.motion.StudyFlowMotion
import dev.studyflow.core.designsystem.navigation.StudyFlowNavigationItem
import dev.studyflow.core.designsystem.navigation.StudyFlowNavigationSuite
import dev.studyflow.core.designsystem.theme.StudyFlowTheme
import dev.studyflow.feature.auth.AccountRoute
import dev.studyflow.feature.materials.MaterialDetailRoute
import dev.studyflow.feature.settings.NotificationSettingsRoute
import dev.studyflow.feature.tasks.TaskDetailRoute
import dev.studyflow.feature.tasks.TasksListRoute
import dev.studyflow.feature.history.HistoryRoute as HistoryScreenRoute
import dev.studyflow.feature.home.HomeRoute as HomeScreenRoute
import dev.studyflow.feature.insights.InsightsRoute as InsightsScreenRoute
import dev.studyflow.feature.insights.WeeklySummaryRoute as WeeklySummaryScreenRoute
import dev.studyflow.feature.materials.MaterialsRoute as MaterialsScreenRoute
import dev.studyflow.feature.settings.DataPrivacyRoute as DataPrivacyScreenRoute
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

        StudyFlowNavigationSuite(
            items = remember { navigationItems() },
            selected = (backStack.lastOrNull() as? AppRoute)?.topLevelRoute,
            onSelect = navigate,
        ) {
            AppNavDisplay(backStack = backStack)
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
                            onOpenSettings = { backStack.add(SettingsRoute) },
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
                        NotificationSettingsRoute(
                            onOpenDataPrivacy = { backStack.add(DataPrivacyRoute) },
                            account = { AccountRoute() },
                        )
                    }
                    entry<DataPrivacyRoute> {
                        DataPrivacyScreenRoute()
                    }
                    entry<HistoryRoute> {
                        HistoryScreenRoute()
                    }
                    entry<InsightsRoute> {
                        InsightsScreenRoute(
                            onOpenWeeklySummary = { backStack.add(WeeklySummaryRoute) },
                            onOpenHistory = { backStack.add(HistoryRoute) },
                        )
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

/**
 * The icons a top-level destination is drawn with: outlined normally, filled when selected.
 *
 * A destination that reaches this and has no icon is a destination that was added to
 * [topLevelRoutes] without being finished, which is worth failing loudly for in a `when` rather
 * than falling back to a placeholder that ships.
 */
private val AppRoute.navigationIcons: Pair<ImageVector, ImageVector>
    get() =
        when (this) {
            HomeRoute -> Icons.Outlined.Home to Icons.Filled.Home
            is TimerRoute -> Icons.Outlined.Timer to Icons.Filled.Timer
            is MaterialsRoute -> Icons.AutoMirrored.Outlined.MenuBook to Icons.AutoMirrored.Filled.MenuBook
            is TasksRoute -> Icons.Outlined.CheckCircle to Icons.Filled.CheckCircle
            InsightsRoute -> Icons.Outlined.Insights to Icons.Filled.Insights
            else -> error("$this is not a top-level destination")
        }

private fun navigationItems(): List<StudyFlowNavigationItem<AppRoute>> =
    topLevelRoutes.map { route ->
        val (icon, selectedIcon) = route.navigationIcons
        StudyFlowNavigationItem(
            key = route,
            label = route.label,
            icon = icon,
            selectedIcon = selectedIcon,
        )
    }
