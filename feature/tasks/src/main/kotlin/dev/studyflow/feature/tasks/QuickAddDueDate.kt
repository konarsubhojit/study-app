package dev.studyflow.feature.tasks

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The due-date shortcuts quick-add offers.
 *
 * A full date picker is one interaction too many for the "create a basic task in two interactions"
 * bar (issue #46); these four cover the cases that account for nearly every due date a study task
 * actually gets, and [Custom] is there for the rest.
 */
public sealed interface QuickAddDueDate {
    public data object None : QuickAddDueDate

    public data object Today : QuickAddDueDate

    public data object Tomorrow : QuickAddDueDate

    public data object NextWeek : QuickAddDueDate

    public data class Custom(
        val date: LocalDate,
    ) : QuickAddDueDate
}

private const val DAYS_UNTIL_NEXT_WEEK = 7

/** Resolves [QuickAddDueDate] against the wall clock at the moment of submission. */
public fun QuickAddDueDate.resolve(
    now: Instant,
    timeZone: TimeZone,
): LocalDate? {
    val today = now.toLocalDateTime(timeZone).date
    return when (this) {
        QuickAddDueDate.None -> null
        QuickAddDueDate.Today -> today
        QuickAddDueDate.Tomorrow -> today.plus(1, DateTimeUnit.DAY)
        QuickAddDueDate.NextWeek -> today.plus(DAYS_UNTIL_NEXT_WEEK, DateTimeUnit.DAY)
        is QuickAddDueDate.Custom -> date
    }
}

/**
 * All-day midnight in the task's time zone — what [dev.studyflow.core.model.StudyTask] requires of
 * an all-day due date, since quick-add collects a date but never a time of day.
 */
public fun LocalDate.atAllDayMidnight(): LocalDateTime = LocalDateTime(this, LocalTime(0, 0))
