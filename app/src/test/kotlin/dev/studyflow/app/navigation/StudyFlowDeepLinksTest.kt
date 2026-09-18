package dev.studyflow.app.navigation

import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudyFlowDeepLinksTest {
    @Test
    fun `every destination round trips through a deep link`() {
        everyDestination.forEach { route ->
            assertEquals(route, StudyFlowDeepLinks.routeFor(StudyFlowDeepLinks.uriFor(route)))
        }
    }

    /**
     * A link the app can parse but the manifest does not advertise is unreachable from outside the
     * app, which is exactly the half of a deep link that is easy to forget and impossible to notice
     * from the Kotlin side alone.
     */
    @Test
    fun `every deep link is routable from outside the app`() {
        val packageManager = RuntimeEnvironment.getApplication().packageManager
        val links =
            everyDestination.map(StudyFlowDeepLinks::uriFor) +
                WidgetAction.entries.map(StudyFlowDeepLinks::uriFor)

        links.forEach { uri ->
            val intent =
                Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            assertNotNull(
                "No activity declares an intent filter for $uri",
                packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY),
            )
        }
    }

    @Test
    fun `every route survives serialization used by the saved back stack`() {
        val routes =
            listOf(
                HomeRoute,
                TimerRoute(openRunningTimer = true),
                MaterialsRoute(materialId = "material-42"),
                TasksRoute(taskId = "task-42"),
                HistoryRoute,
                SettingsRoute,
            )

        routes.forEach { route ->
            val encoded = Json.encodeToString(AppRoute.serializer(), route)
            assertEquals(route, Json.decodeFromString(AppRoute.serializer(), encoded))
        }
    }

    @Test
    fun `every widget action resolves to a typed destination`() {
        WidgetAction.entries.forEach { action ->
            val route = StudyFlowDeepLinks.routeFor(StudyFlowDeepLinks.uriFor(action))
            assertEquals(action.expectedRoute, route)
        }
    }

    @Test
    fun `unknown and malformed links are rejected`() {
        assertNull(StudyFlowDeepLinks.routeFor("https://tasks/42".toUri()))
        assertNull(StudyFlowDeepLinks.routeFor("studyflow://tasks/42/extra".toUri()))
        assertNull(StudyFlowDeepLinks.routeFor("studyflow://unknown".toUri()))
        assertNull(StudyFlowDeepLinks.routeFor(null))
    }

    @Test
    fun `restoration ignores a consumed link but handles a new link`() {
        val consumed = StudyFlowDeepLinks.uriFor(TasksRoute(taskId = "old-task"))
        val incoming = StudyFlowDeepLinks.uriFor(TasksRoute(taskId = "new-task"))

        assertNull(StudyFlowDeepLinks.routeForNewIntent(consumed, consumed.toString()))
        assertEquals(
            TasksRoute(taskId = "new-task"),
            StudyFlowDeepLinks.routeForNewIntent(incoming, consumed.toString()),
        )
    }

    /** One link per destination shape, shared by the tests that must all see a new route. */
    private val everyDestination =
        listOf(
            HomeRoute,
            TimerRoute(),
            TimerRoute(openRunningTimer = true),
            MaterialsRoute(),
            MaterialsRoute(materialId = "material / 42"),
            TasksRoute(),
            TasksRoute(taskId = "task / 42"),
            HistoryRoute,
            InsightsRoute,
            SettingsRoute,
        )

    private val WidgetAction.expectedRoute: AppRoute
        get() =
            when (this) {
                WidgetAction.HOME -> HomeRoute
                WidgetAction.TIMER -> TimerRoute(openRunningTimer = true)
                WidgetAction.MATERIALS -> MaterialsRoute()
                WidgetAction.TASKS -> TasksRoute()
                WidgetAction.HISTORY -> HistoryRoute
                WidgetAction.INSIGHTS -> InsightsRoute
                WidgetAction.SETTINGS -> SettingsRoute
            }
}
