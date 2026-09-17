package dev.studyflow.feature.tasks

import dev.studyflow.core.model.ReminderTrigger
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The lead times the detail screen offers for a new reminder.
 *
 * A full time picker for an arbitrary [ReminderTrigger.AtInstant] is more control than a study
 * reminder needs and is a larger surface to get right (time zones, DST); these presets cover what
 * users actually ask for and stay entirely in [ReminderTrigger.BeforeDue], which follows the task
 * when it moves or recurs.
 */
public enum class ReminderPreset(
    public val label: String,
    public val trigger: ReminderTrigger,
) {
    AT_DUE_TIME("At due time", ReminderTrigger.BeforeDue()),
    TEN_MINUTES_BEFORE("10 minutes before", ReminderTrigger.BeforeDue(10.minutes)),
    ONE_HOUR_BEFORE("1 hour before", ReminderTrigger.BeforeDue(1.hours)),
    ONE_DAY_BEFORE("1 day before", ReminderTrigger.BeforeDue(1.days)),
}
