package dev.studyflow.core.notifications

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidNotificationPermissionReaderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val application = shadowOf(context)
    private val requestLog = FakeRequestLog()

    @Test
    fun `a granted permission is granted`() {
        application.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertEquals(NotificationPermissionStatus.GRANTED, reader().currentState().status)
        assertTrue(reader().currentState().canPost)
    }

    @Test
    fun `never asked is told apart from permanently denied by our own record`() {
        application.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertEquals(NotificationPermissionStatus.NOT_REQUESTED, reader().currentState().status)

        requestLog.recordRequested()

        assertEquals(NotificationPermissionStatus.PERMANENTLY_DENIED, reader().currentState().status)
    }

    @Test
    fun `a denial the system will still explain becomes DENIED`() {
        application.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        requestLog.recordRequested()

        val state = reader(shouldShowRationale = true).currentState()

        assertEquals(NotificationPermissionStatus.DENIED, state.status)
        assertFalse(state.canPost)
    }

    @Test
    fun `below Android 13 the permission does not exist`() {
        application.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val state = reader(sdkInt = Build.VERSION_CODES.S_V2).currentState()

        assertEquals(NotificationPermissionStatus.NOT_REQUIRED, state.status)
        assertTrue(state.canPost)
    }

    @Test
    fun `switching notifications off blocks posting whatever the permission says`() {
        application.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationsEnabled(false)

        val state = reader().currentState()

        assertEquals(NotificationPermissionStatus.GRANTED, state.status)
        assertFalse(state.canPost)
    }

    private fun reader(
        shouldShowRationale: Boolean = false,
        sdkInt: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
    ) = AndroidNotificationPermissionReader(
        context = context,
        requestLog = requestLog,
        shouldShowRationale = { shouldShowRationale },
        sdkInt = sdkInt,
    )

    private class FakeRequestLog : NotificationPermissionRequestLog {
        private var requested = false

        override fun hasRequested(): Boolean = requested

        override fun recordRequested() {
            requested = true
        }
    }
}
