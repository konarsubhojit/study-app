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
        val outcome =
            when (plan.delivery) {
                ReminderDelivery.INEXACT -> platformScheduler.scheduleInexact(plan)
                ReminderDelivery.EXACT -> platformScheduler.scheduleExact(plan)
                ReminderDelivery.ALARM_CLOCK -> platformScheduler.scheduleAlarmClock(plan)
            }
        val effectivePlan = plan.withOutcome(outcome)

        return ReminderScheduleResult.Scheduled(
            plan = effectivePlan,
            permissionRationale = ExactAlarmPermissionRationale.takeIfNeeded(effectivePlan),
            banner = ReducedPrecisionBanner.takeIfNeeded(effectivePlan),
        )
    }

    public fun rescheduleAll(tasks: Collection<StudyTask>): List<ReminderScheduleResult> = tasks.map(::schedule)

    public fun replace(
        previousReminderId: String?,
        task: StudyTask,
    ): ReminderScheduleResult {
        val nextReminderId = task.reminder?.id
        if (previousReminderId != null && previousReminderId != nextReminderId) {
            platformScheduler.cancel(previousReminderId)
        }
        return schedule(task)
    }

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
    public fun scheduleInexact(plan: ReminderPlan): PlatformScheduleOutcome

    public fun scheduleExact(plan: ReminderPlan): PlatformScheduleOutcome

    public fun scheduleAlarmClock(plan: ReminderPlan): PlatformScheduleOutcome

    public fun cancel(reminderId: String)
}

public enum class PlatformScheduleOutcome {
    SCHEDULED,
    FALLBACK_TO_INEXACT,
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
    val titleKey: ReminderSchedulingMessageKey,
    val messageKey: ReminderSchedulingMessageKey,
    val settingsAction: String,
) {
    public companion object {
        public const val REQUEST_EXACT_ALARM_SETTINGS: String =
            "android.settings.REQUEST_SCHEDULE_EXACT_ALARM"

        public fun takeIfNeeded(plan: ReminderPlan): ExactAlarmPermissionRationale? =
            if (plan.hasExactAlarmDenial) {
                ExactAlarmPermissionRationale(
                    titleKey = ReminderSchedulingMessageKey.EXACT_ALARM_PERMISSION_TITLE,
                    messageKey = ReminderSchedulingMessageKey.EXACT_ALARM_PERMISSION_MESSAGE,
                    settingsAction = REQUEST_EXACT_ALARM_SETTINGS,
                )
            } else {
                null
            }
    }
}

public data class ReducedPrecisionBanner(
    val messageKey: ReminderSchedulingMessageKey,
) {
    public companion object {
        public fun takeIfNeeded(plan: ReminderPlan): ReducedPrecisionBanner? =
            if (plan.hasExactAlarmDenial) {
                ReducedPrecisionBanner(
                    messageKey = ReminderSchedulingMessageKey.REDUCED_PRECISION_BANNER,
                )
            } else {
                null
            }
    }
}

public enum class ReminderSchedulingMessageKey {
    EXACT_ALARM_PERMISSION_TITLE,
    EXACT_ALARM_PERMISSION_MESSAGE,
    REDUCED_PRECISION_BANNER,
}

private fun ReminderPlan.withOutcome(outcome: PlatformScheduleOutcome): ReminderPlan =
    when (outcome) {
        PlatformScheduleOutcome.SCHEDULED -> {
            this
        }

        PlatformScheduleOutcome.FALLBACK_TO_INEXACT -> {
            copy(
                delivery = ReminderDelivery.INEXACT,
                degradations = degradations + ReminderDegradation.EXACT_ALARMS_DENIED,
            )
        }
    }

private val ReminderPlan.hasExactAlarmDenial: Boolean
    get() = ReminderDegradation.EXACT_ALARMS_DENIED in degradations
