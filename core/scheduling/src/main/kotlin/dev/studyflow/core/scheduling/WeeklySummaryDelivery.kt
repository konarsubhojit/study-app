package dev.studyflow.core.scheduling

import android.content.Context
import android.net.Uri
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.datastore.WeeklySummaryDeliveryLog
import dev.studyflow.core.datastore.WeeklySummarySettings
import dev.studyflow.core.domain.stats.WeeklySummary
import dev.studyflow.core.domain.stats.WeeklySummaryCopy
import dev.studyflow.core.domain.stats.WeeklySummaryProvider
import dev.studyflow.core.domain.subjects.SubjectRepository
import dev.studyflow.core.notifications.NotificationPostResult
import dev.studyflow.core.notifications.StudyFlowNotificationChannel
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import dev.studyflow.core.notifications.StudyFlowNotifier
import dev.studyflow.core.notifications.StudyFlowPendingIntents
import kotlinx.coroutines.flow.first

/** The single notification the weekly summary posts, replacing whichever week came before it. */
internal const val WEEKLY_SUMMARY_NOTIFICATION_ID = 0x53_74_46_77 // "StFw", arbitrary but stable.

private const val NO_SUBJECT = "No subject"

/** Why a weekly summary run ended the way it did — the thing worth asserting about in a test. */
public enum class WeeklySummaryOutcome {
    /** The recap was posted; the week is recorded so it cannot be posted again. */
    DELIVERED,

    /** The user has the weekly summary switched off. */
    OPTED_OUT,

    /** This week's recap has already been delivered. */
    ALREADY_DELIVERED,

    /** Nothing was studied — skipped entirely rather than sent as "you studied 0 hours". */
    INACTIVE_WEEK,

    /** The permission or the channel is off, so nothing could be shown. */
    NOT_ALLOWED,
}

/**
 * Decides whether this week's recap should be delivered, and delivers it (issue #63).
 *
 * Separate from [WeeklySummaryWorker] for the same reason [MaterialUploadEngine] is separate from
 * its worker: the rules below are the part worth testing, and they need nothing from WorkManager.
 *
 * Four gates, in order, each of them a requirement rather than an optimisation:
 *
 * 1. **Opted out** — read on every run rather than trusted from schedule time, so a cancellation
 *    that raced an already-queued run still silences it.
 * 2. **Already delivered** — the week's start day is recorded after a successful post, so a
 *    WorkManager retry, or a re-enqueue caused by the user moving the delivery time mid-week,
 *    cannot produce a second recap for the same week.
 * 3. **Nothing happened** — an inactive week is skipped entirely; "you studied 0 hours" is the one
 *    message a recap must never send.
 * 4. **Not allowed to post** — the permission or the channel is off, in which case the run is
 *    dropped *without* recording it, so next week's recap is unaffected.
 */
public class WeeklySummaryDelivery(
    private val context: Context,
    private val settings: WeeklySummarySettings,
    private val deliveryLog: WeeklySummaryDeliveryLog,
    private val summaryProvider: WeeklySummaryProvider,
    private val subjectRepository: SubjectRepository,
    private val notifier: StudyFlowNotifier,
    private val notificationFactory: StudyFlowNotificationFactory,
    private val clock: Clock,
) {
    public suspend fun deliverIfDue(): WeeklySummaryOutcome {
        if (!settings.schedule.first().enabled) return WeeklySummaryOutcome.OPTED_OUT

        val summary = summaryProvider.summaryAt(clock.now())
        val weekStart = summary.window.start.toEpochDays()
        if (deliveryLog.lastDeliveredWeekStart() >= weekStart) return WeeklySummaryOutcome.ALREADY_DELIVERED
        if (!summary.hasActivity) return WeeklySummaryOutcome.INACTIVE_WEEK
        if (!notifier.canPost(StudyFlowNotificationChannel.WEEKLY_SUMMARY)) return WeeklySummaryOutcome.NOT_ALLOWED

        if (post(summary) != NotificationPostResult.POSTED) return WeeklySummaryOutcome.NOT_ALLOWED
        deliveryLog.recordDelivered(weekStart)
        return WeeklySummaryOutcome.DELIVERED
    }

    private suspend fun post(summary: WeeklySummary): NotificationPostResult {
        val subjects = subjectRepository.observeSubjects().first()
        val body =
            WeeklySummaryCopy.body(summary) { subjectId ->
                subjects.firstOrNull { it.id == subjectId }?.name ?: NO_SUBJECT
            }
        val notification =
            notificationFactory.alert(
                channel = StudyFlowNotificationChannel.WEEKLY_SUMMARY,
                title = WeeklySummaryCopy.title(summary),
                // `alert` renders this as BigTextStyle, so the whole recap is one expansion away.
                text = body,
                contentIntent =
                    StudyFlowPendingIntents.activity(
                        context,
                        WEEKLY_SUMMARY_NOTIFICATION_ID,
                        weeklySummaryDeepLink(),
                    ),
            )
        return notifier.post(
            WEEKLY_SUMMARY_NOTIFICATION_ID,
            StudyFlowNotificationChannel.WEEKLY_SUMMARY,
            notification,
        )
    }
}

/** `studyflow://summary` — the full recap screen the notification links to. */
private fun weeklySummaryDeepLink(): Uri =
    Uri
        .Builder()
        .scheme("studyflow")
        .authority("summary")
        .build()
