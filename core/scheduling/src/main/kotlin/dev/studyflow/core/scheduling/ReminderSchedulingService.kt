package dev.studyflow.core.scheduling

import dev.studyflow.core.domain.reminder.ReminderDegradation
import dev.studyflow.core.domain.reminder.ReminderDelivery
import dev.studyflow.core.domain.reminder.ReminderPlan
import dev.studyflow.core.domain.reminder.ReminderScheduler
import dev.studyflow.core.domain.reminder.SchedulingCapabilities
import dev.studyflow.core.model.StudyTask
import kotlin.time.Instant

/**
 * Runtime façade for reminder scheduling.
 *
 * It reads platform capabilities for every call so permission changes are reflected immediately,
 * delegates the decision matrix to [ReminderScheduler], and replaces any existing registration for
 * the same reminder id before scheduling the new one.
 */
public class ReminderSchedulingService(
    private val capabilitiesProvider: SchedulingCapabilitiesProvider,
    private val platformScheduler: ReminderPlatformScheduler,
    private val now: () -> Instant,
) {
    public fun schedule(task: StudyTask): ReminderScheduleResult {
        val reminderId = task.reminder?.id
        val capabilities = capabilitiesProvider.currentCapabilities()
        val plan = ReminderScheduler.plan(task, capabilities, now())

        if (plan == null) {
            if (reminderId != null) {
                platformScheduler.cancel(reminderId)
            }
            return ReminderScheduleResult.NotScheduled(reminderId = reminderId)
        }

        platformScheduler.cancel(plan.reminderId)
        when (plan.delivery) {
            ReminderDelivery.INEXACT -> platformScheduler.scheduleInexact(plan)
            ReminderDelivery.EXACT -> platformScheduler.scheduleExact(plan)
            ReminderDelivery.ALARM_CLOCK -> platformScheduler.scheduleAlarmClock(plan)
        }

        return ReminderScheduleResult.Scheduled(
            plan = plan,
            permissionRationale = ExactAlarmPermissionRationale.takeIfNeeded(plan),
            banner = ReducedPrecisionBanner.takeIfNeeded(plan),
        )
    }

    public fun rescheduleAll(tasks: Collection<StudyTask>): List<ReminderScheduleResult> = tasks.map(::schedule)

    public fun cancel(reminderId: String) {
        platformScheduler.cancel(reminderId)
    }
}

/** Reads platform state that can change while the app is not running. */
public fun interface SchedulingCapabilitiesProvider {
    public fun currentCapabilities(): SchedulingCapabilities
}

/** Platform-specific registration primitives. Implementations must replace existing ids. */
public interface ReminderPlatformScheduler {
    public fun scheduleInexact(plan: ReminderPlan)

    public fun scheduleExact(plan: ReminderPlan)

    public fun scheduleAlarmClock(plan: ReminderPlan)

    public fun cancel(reminderId: String)
}

public sealed interface ReminderScheduleResult {
    public data class Scheduled(
        val plan: ReminderPlan,
        val permissionRationale: ExactAlarmPermissionRationale? = null,
        val banner: ReducedPrecisionBanner? = null,
    ) : ReminderScheduleResult

    public data class NotScheduled(
        val reminderId: String?,
    ) : ReminderScheduleResult
}

public data class ExactAlarmPermissionRationale(
    val title: String,
    val message: String,
    val settingsAction: String,
) {
    public companion object {
        public const val REQUEST_EXACT_ALARM_SETTINGS: String =
            "android.settings.REQUEST_SCHEDULE_EXACT_ALARM"

        public fun takeIfNeeded(plan: ReminderPlan): ExactAlarmPermissionRationale? =
            if (ReminderDegradation.EXACT_ALARMS_DENIED in plan.degradations) {
                ExactAlarmPermissionRationale(
                    title = "Allow exact reminders",
                    message =
                        "This reminder was scheduled with reduced precision because Android " +
                            "does not currently allow exact alarms for StudyFlow.",
                    settingsAction = REQUEST_EXACT_ALARM_SETTINGS,
                )
            } else {
                null
            }
    }
}

public data class ReducedPrecisionBanner(
    val message: String,
) {
    public companion object {
        public fun takeIfNeeded(plan: ReminderPlan): ReducedPrecisionBanner? =
            if (ReminderDegradation.EXACT_ALARMS_DENIED in plan.degradations) {
                ReducedPrecisionBanner(
                    message =
                        "Exact alarms are off, so this reminder may arrive later than the " +
                            "selected minute.",
                )
            } else {
                null
            }
    }
}
