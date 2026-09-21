package dev.studyflow.core.scheduling

import androidx.core.app.NotificationManagerCompat
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.datastore.WeeklySummarySchedule
import dev.studyflow.core.domain.stats.WeeklySummaryProvider
import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.notifications.NotificationChannelRegistrar
import dev.studyflow.core.notifications.NotificationPermissionState
import dev.studyflow.core.notifications.NotificationPermissionStatus
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import dev.studyflow.core.notifications.StudyFlowNotifier
import dev.studyflow.core.testing.data.FakeStatsRepository
import dev.studyflow.core.testing.data.FakeSubjectRepository
import dev.studyflow.core.testing.data.testStudySession
import dev.studyflow.core.testing.data.testSubject
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The delivery rules from issue #63's acceptance criteria: once a week, never for an empty week,
 * and never at all once the user has opted out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklySummaryDeliveryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = NotificationManagerCompat.from(context)
    private var notificationsEnabled = true
    private val notifier =
        StudyFlowNotifier(
            registrar = NotificationChannelRegistrar(context, manager),
            permissions = {
                NotificationPermissionState(
                    if (notificationsEnabled) {
                        NotificationPermissionStatus.GRANTED
                    } else {
                        NotificationPermissionStatus.DENIED
                    },
                    notificationsEnabled = notificationsEnabled,
                )
            },
            manager = manager,
        )
    private val timeZoneProvider = TimeZoneProvider { TimeZone.UTC }
    private val statsRepository = FakeStatsRepository(timeZoneProvider)
    private val subjectRepository = FakeSubjectRepository()
    private val settings = FakeWeeklySummarySettings(WeeklySummarySchedule(enabled = true))
    private val deliveryLog = FakeWeeklySummaryDeliveryLog()
    private val delivery =
        WeeklySummaryDelivery(
            context = context,
            settings = settings,
            deliveryLog = deliveryLog,
            summaryProvider = WeeklySummaryProvider(statsRepository, timeZoneProvider),
            subjectRepository = subjectRepository,
            notifier = notifier,
            notificationFactory = StudyFlowNotificationFactory(context, android.R.drawable.ic_dialog_info),
            clock = Clock { NOW },
        )

    @Test
    fun `a week with study time is delivered once, as an expanded recap`() =
        runBlocking {
            subjectRepository.put(testSubject(id = "maths", name = "Mathematics"))
            statsRepository.put(session("s1", "2026-03-04T10:00:00Z", 130))

            assertEquals(WeeklySummaryOutcome.DELIVERED, delivery.deliverIfDue())

            val notification = shadowManager().getNotification(WEEKLY_SUMMARY_NOTIFICATION_ID)
            assertEquals(
                "2 h 10 min studied last week",
                notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString(),
            )
            val body = notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString()
            assertTrue(body, body.contains("Mathematics 2 h 10 min"))
            assertTrue(body, body.contains("30% of your 7 h 0 min weekly goal"))
            assertTrue(body, body.contains("2026-03-02 to 2026-03-08"))
        }

    @Test
    fun `the same week is never delivered twice`() =
        runBlocking {
            statsRepository.put(session("s1", "2026-03-04T10:00:00Z", 60))

            assertEquals(WeeklySummaryOutcome.DELIVERED, delivery.deliverIfDue())
            assertEquals(WeeklySummaryOutcome.ALREADY_DELIVERED, delivery.deliverIfDue())
        }

    @Test
    fun `an inactive week is skipped rather than announced as zero hours`() =
        runBlocking {
            statsRepository.put(session("older", "2026-02-20T10:00:00Z", 60))

            assertEquals(WeeklySummaryOutcome.INACTIVE_WEEK, delivery.deliverIfDue())
            assertEquals(0, shadowManager().size())
        }

    @Test
    fun `opting out stops delivery immediately, even for a run already queued`() =
        runBlocking {
            statsRepository.put(session("s1", "2026-03-04T10:00:00Z", 60))
            settings.setEnabled(false)

            assertEquals(WeeklySummaryOutcome.OPTED_OUT, delivery.deliverIfDue())
            assertEquals(0, shadowManager().size())
        }

    @Test
    fun `a blocked notification is not recorded, so next week is unaffected`() =
        runBlocking {
            statsRepository.put(session("s1", "2026-03-04T10:00:00Z", 60))
            notificationsEnabled = false

            assertEquals(WeeklySummaryOutcome.NOT_ALLOWED, delivery.deliverIfDue())
            assertEquals(-1, deliveryLog.lastDeliveredWeekStart())
        }

    private fun shadowManager() = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))

    private fun session(
        id: String,
        startedAt: String,
        countedMinutes: Int,
    ): StudySession =
        testStudySession(
            id = id,
            subjectId = "maths",
            startedAt = Instant.parse(startedAt),
            endedAt = Instant.parse(startedAt) + countedMinutes.minutes,
            status = SessionStatus.STOPPED,
            elapsed = SessionElapsed(counted = countedMinutes.minutes),
        )

    private companion object {
        /** Sunday, 2026-03-08, 18:00 UTC — the default delivery moment. */
        val NOW: Instant = Instant.parse("2026-03-08T18:00:00Z")
    }
}
