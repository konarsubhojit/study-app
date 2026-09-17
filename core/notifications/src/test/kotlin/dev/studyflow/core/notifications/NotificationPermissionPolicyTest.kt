package dev.studyflow.core.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("NotificationPermissionPolicy")
class NotificationPermissionPolicyTest {
    @Test
    fun `cold start never asks, however bad the permission state is`() {
        NotificationPermissionStatus.entries.forEach { status ->
            val action =
                NotificationPermissionPolicy.actionFor(
                    state(status, notificationsEnabled = false),
                    NotificationMoment.APP_LAUNCH,
                )

            assertEquals(NotificationPermissionAction.None, action, "asked at cold start for $status")
        }
    }

    @Test
    fun `a moment of value asks when the dialog has never been shown`() {
        val action =
            NotificationPermissionPolicy.actionFor(
                state(NotificationPermissionStatus.NOT_REQUESTED),
                NotificationMoment.STARTING_TIMER,
            )

        assertEquals(NotificationPermissionAction.RequestPermission, action)
    }

    @Test
    fun `a first denial earns an explanation before asking again`() {
        val action =
            NotificationPermissionPolicy.actionFor(
                state(NotificationPermissionStatus.DENIED),
                NotificationMoment.SAVING_REMINDER,
            )

        assertEquals(
            NotificationPermissionAction.ShowRationale(NotificationMessageKey.RATIONALE_REMINDER),
            action,
        )
    }

    @Test
    fun `a permanent denial sends the user to settings with what they are losing`() {
        val action =
            NotificationPermissionPolicy.actionFor(
                state(NotificationPermissionStatus.PERMANENTLY_DENIED),
                NotificationMoment.SAVING_ALARM,
            )

        assertEquals(
            NotificationPermissionAction.OpenSystemSettings(
                degradationKey = NotificationMessageKey.DEGRADED_ALARM,
                channel = StudyFlowNotificationChannel.ALARMS,
            ),
            action,
        )
    }

    @Test
    fun `granted but switched off app-wide is a settings problem, not a permission one`() {
        val action =
            NotificationPermissionPolicy.actionFor(
                state(NotificationPermissionStatus.GRANTED, notificationsEnabled = false),
                NotificationMoment.STARTING_UPLOAD,
            )

        assertEquals(
            NotificationPermissionAction.OpenSystemSettings(
                degradationKey = NotificationMessageKey.DEGRADED_UPLOAD,
                channel = StudyFlowNotificationChannel.UPLOADS,
            ),
            action,
        )
    }

    @Test
    fun `below Android 13 the permission is held but the switch still decides`() {
        val allowed = state(NotificationPermissionStatus.NOT_REQUIRED, notificationsEnabled = true)
        val blocked = state(NotificationPermissionStatus.NOT_REQUIRED, notificationsEnabled = false)

        assertEquals(
            NotificationPermissionAction.None,
            NotificationPermissionPolicy.actionFor(allowed, NotificationMoment.STARTING_TIMER),
        )
        assertEquals(
            NotificationPermissionAction.OpenSystemSettings(
                degradationKey = NotificationMessageKey.DEGRADED_TIMER,
                channel = StudyFlowNotificationChannel.STUDY_TIMER,
            ),
            NotificationPermissionPolicy.actionFor(blocked, NotificationMoment.STARTING_TIMER),
        )
    }

    @Test
    fun `a working permission asks for nothing and degrades nothing`() {
        val granted = state(NotificationPermissionStatus.GRANTED)

        assertEquals(
            NotificationPermissionAction.None,
            NotificationPermissionPolicy.actionFor(granted, NotificationMoment.SAVING_REMINDER),
        )
        assertNull(NotificationPermissionPolicy.degradationFor(granted, NotificationMoment.SAVING_REMINDER))
    }

    @Test
    fun `every prompting moment names what stops working`() {
        val denied = state(NotificationPermissionStatus.PERMANENTLY_DENIED, notificationsEnabled = false)

        NotificationMoment.entries.forEach { moment ->
            assertEquals(
                moment.degradationKey,
                NotificationPermissionPolicy.degradationFor(denied, moment),
                "${moment.name} does not say what the user loses",
            )
        }
    }

    private fun state(
        status: NotificationPermissionStatus,
        notificationsEnabled: Boolean = true,
    ) = NotificationPermissionState(status = status, notificationsEnabled = notificationsEnabled)
}
