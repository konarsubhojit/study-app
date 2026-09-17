package dev.studyflow.app.navigation

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.feature.settings.NotificationSettingsRoute
import dev.studyflow.feature.tasks.TaskDetailRoute
import dev.studyflow.feature.tasks.TasksListRoute

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
                        DestinationScreen("Home")
                    }
                    entry<TimerRoute> { route ->
                        DestinationScreen(
                            if (route.openRunningTimer) "Running timer" else "Timer",
                        )
                    }
                    entry<MaterialsRoute> { route ->
                        DestinationScreen(
                            route.materialId?.let { "Material: $it" } ?: "Materials",
                        )
                    }
                    entry<TasksRoute> { route ->
                        if (route.taskId == null) {
                            TasksListRoute(
                                onTaskSelected = { taskId ->
                                    if (backStack.lastOrNull() != TasksRoute(taskId)) {
                                        backStack.add(TasksRoute(taskId))
                                    }
                                },
                            )
                        } else {
                            TaskDetailRoute(
                                taskId = route.taskId,
                                onBack = { backStack.removeLastOrNull() },
                            )
                        }
                    }
                    entry<SettingsRoute> {
                        NotificationSettingsRoute()
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
private fun DestinationScreen(title: String) {
    var restoredNote by rememberSaveable { mutableStateOf("") }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.large),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = restoredNote,
            onValueChange = { restoredNote = it },
            label = { Text("Saved screen note") },
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
            SettingsRoute -> SettingsRoute
        }

private val AppRoute.label: String
    get() =
        when (this) {
            HomeRoute -> "Home"
            is TimerRoute -> "Timer"
            is MaterialsRoute -> "Materials"
            is TasksRoute -> "Tasks"
            SettingsRoute -> "Settings"
        }
