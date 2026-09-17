package dev.studyflow.core.scheduling

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.studyflow.core.datastore.UserSettingsStore
import dev.studyflow.core.domain.tasks.TaskRepository
import dev.studyflow.core.notifications.AlertPresentation
import dev.studyflow.core.notifications.StudyFlowNotificationChannel
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import dev.studyflow.core.notifications.StudyFlowNotifier
import dev.studyflow.core.notifications.StudyFlowPendingIntents
import kotlinx.coroutines.flow.first

/** The single notification the digest posts, replacing whichever day's summary came before it. */
internal const val DIGEST_NOTIFICATION_ID = 0x53_74_46_6c // "StFl", arbitrary but stable.

/**
 * "N tasks due today", once a day, instead of one ping per task (issue #47).
 *
 * Reads `UserSettings.digestEnabled` on every run rather than once at schedule time, so turning
 * the setting off silences the *next* run without needing to cancel and re-enqueue this worker.
 * When it is off this is a fast no-op — the individual reminders [ReminderSchedulingService]
 * already scheduled are what the user sees instead, and [ReminderDeliveryCoordinator] is the
 * other half of not double-notifying: it stays silent per-task while the digest is on.
 */
@HiltWorker
public class DigestNotificationWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted parameters: WorkerParameters,
        private val settingsStore: UserSettingsStore,
        private val taskRepository: TaskRepository,
        private val notifier: StudyFlowNotifier,
        private val notificationFactory: StudyFlowNotificationFactory,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            val settings = settingsStore.data.first()
            val dueToday =
                if (settings.digestEnabled) {
                    taskRepository.observeToday().first().filterNot { it.isCompleted }
                } else {
                    emptyList()
                }

            if (dueToday.isEmpty()) {
                notifier.cancel(DIGEST_NOTIFICATION_ID)
                return Result.success()
            }

            val contentIntent =
                StudyFlowPendingIntents.activity(
                    applicationContext,
                    DIGEST_NOTIFICATION_ID,
                    tasksListDeepLink(),
                )
            val publicVersion =
                notificationFactory.alert(
                    channel = StudyFlowNotificationChannel.TASK_REMINDERS,
                    title = "You have tasks due today",
                    text = "",
                    contentIntent = null,
                )
            val notification =
                notificationFactory.alert(
                    channel = StudyFlowNotificationChannel.TASK_REMINDERS,
                    title = "${dueToday.size} tasks due today",
                    text = dueToday.joinToString(", ") { it.title },
                    contentIntent = contentIntent,
                    presentation = AlertPresentation(publicVersion = publicVersion),
                )
            notifier.post(DIGEST_NOTIFICATION_ID, StudyFlowNotificationChannel.TASK_REMINDERS, notification)
            return Result.success()
        }
    }

private fun tasksListDeepLink(): Uri =
    Uri
        .Builder()
        .scheme("studyflow")
        .authority("tasks")
        .build()
