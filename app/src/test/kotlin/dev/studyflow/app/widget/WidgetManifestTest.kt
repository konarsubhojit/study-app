package dev.studyflow.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import dev.studyflow.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetManifestTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun `both widgets are registered for the launcher's update broadcast`() {
        listOf(TimerWidgetReceiver::class.java, TodayTasksWidgetReceiver::class.java).forEach { receiver ->
            val actions =
                shadowOf(app.packageManager)
                    .getIntentFiltersForReceiver(ComponentName(app, receiver))
                    .flatMap { filter -> (0 until filter.countActions()).map(filter::getAction) }

            assertTrue(
                "${receiver.simpleName} is not registered for ${AppWidgetManager.ACTION_APPWIDGET_UPDATE}",
                AppWidgetManager.ACTION_APPWIDGET_UPDATE in actions,
            )
        }
    }

    @Test
    fun `no widget asks the platform for periodic updates`() {
        // The acceptance criterion this guards is "no periodic update job burns battery when
        // nothing is happening": every refresh is pushed by a committed session command instead.
        listOf(R.xml.widget_timer_info, R.xml.widget_tasks_info).forEach { info ->
            assertEquals(
                "widget provider $info declares a periodic update",
                "0",
                app.resources.getXml(info).updatePeriodMillis(),
            )
        }
    }

    @Test
    fun `the quick settings tile can only be bound by the system`() {
        val service =
            app.packageManager.getServiceInfo(
                ComponentName(app, StudyTimerTileService::class.java),
                PackageManager.GET_META_DATA,
            )

        assertEquals(android.Manifest.permission.BIND_QUICK_SETTINGS_TILE, service.permission)
        assertTrue(
            "the tile must act on a tap rather than open the app",
            service.metaData.getBoolean(TileService.META_DATA_ACTIVE_TILE) ||
                service.metaData.getBoolean("android.service.quicksettings.TOGGLEABLE_TILE"),
        )
    }

    @Test
    fun `the quick settings tile answers the platform's tile intent`() {
        val tiles =
            app.packageManager.queryIntentServices(
                Intent(TileService.ACTION_QS_TILE).setPackage(app.packageName),
                0,
            )

        assertTrue(
            "no service handles ${TileService.ACTION_QS_TILE}",
            tiles.any { it.serviceInfo.name == StudyTimerTileService::class.java.name },
        )
    }
}

/** Reads `updatePeriodMillis` out of a parsed `appwidget-provider` resource. */
private fun android.content.res.XmlResourceParser.updatePeriodMillis(): String? =
    use { parser ->
        generateSequence { parser.next().takeIf { it != org.xmlpull.v1.XmlPullParser.END_DOCUMENT } }
            .firstOrNull { it == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "appwidget-provider" }
            ?.let {
                (0 until parser.attributeCount)
                    .firstOrNull { index -> parser.getAttributeName(index) == "updatePeriodMillis" }
                    ?.let(parser::getAttributeValue)
            }
    }
