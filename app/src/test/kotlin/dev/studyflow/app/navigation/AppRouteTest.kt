package dev.studyflow.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation surface contract (issue #167): Material 3 allows a navigation bar three to five
 * destinations, and every destination that is not in the bar still has to belong somewhere, or it
 * silently becomes unreachable.
 */
class AppRouteTest {
    @Test
    fun `the navigation bar stays within the Material maximum`() {
        assertTrue("A navigation bar holds at most five destinations", topLevelRoutes.size <= 5)
    }

    @Test
    fun `every bar destination selects itself`() {
        topLevelRoutes.forEach { route ->
            assertEquals(route, route.topLevelRoute)
        }
    }

    @Test
    fun `a detail of a bar destination keeps that destination selected`() {
        assertEquals(MaterialsRoute(), MaterialsRoute("material-1").topLevelRoute)
        assertEquals(TasksRoute(), TasksRoute("task-1").topLevelRoute)
        assertEquals(TimerRoute(), TimerRoute(openRunningTimer = true).topLevelRoute)
    }

    @Test
    fun `history and the weekly recap are read as insights`() {
        assertEquals(InsightsRoute, HistoryRoute.topLevelRoute)
        assertEquals(InsightsRoute, WeeklySummaryRoute.topLevelRoute)
    }

    @Test
    fun `settings is opened from an app bar action, so nothing in the bar is selected for it`() {
        assertNull(SettingsRoute.topLevelRoute)
        assertNull(DataPrivacyRoute.topLevelRoute)
    }

    @Test
    fun `every destination has a label of its own`() {
        val labels =
            listOf(
                HomeRoute,
                TimerRoute(),
                MaterialsRoute(),
                TasksRoute(),
                HistoryRoute,
                InsightsRoute,
                WeeklySummaryRoute,
                SettingsRoute,
                DataPrivacyRoute,
            ).map { it.label }

        assertEquals(labels.size, labels.distinct().size)
        assertTrue("A label is what the bar and the app bar show", labels.none(String::isBlank))
    }
}
