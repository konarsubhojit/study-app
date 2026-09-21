package dev.studyflow.core.domain.stats

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** How many local days one weekly cadence spans. */
private const val DAYS_PER_WEEK = 7

/**
 * When the next weekly summary is due, in wall-clock terms (issue #63).
 *
 * Pure date maths, kept out of `:core:scheduling` so the awkward cases — the chosen day being
 * today but the time already past, a DST shift moving the chosen hour — are JVM-testable without
 * WorkManager. Resolved through [kotlinx.datetime.LocalDateTime] rather than by adding a fixed
 * number of hours, so "Sunday at 18:00" stays 18:00 across a DST boundary instead of drifting by
 * an hour (docs/adr/0004).
 */
public object WeeklyDelivery {
    /**
     * The first instant strictly after [now] that falls on [isoDayOfWeek] at [hour]:[minute] local.
     *
     * @param isoDayOfWeek ISO-8601 numbering, 1 = Monday … 7 = Sunday.
     */
    public fun nextOccurrence(
        now: Instant,
        zone: TimeZone,
        isoDayOfWeek: Int,
        hour: Int,
        minute: Int,
    ): Instant {
        require(isoDayOfWeek in 1..DAYS_PER_WEEK) { "isoDayOfWeek must be 1..7, was $isoDayOfWeek" }
        val today = now.toLocalDateTime(zone).date
        val daysAhead = ((isoDayOfWeek - today.dayOfWeek.isoDayNumber) + DAYS_PER_WEEK) % DAYS_PER_WEEK
        val candidate = today.plus(daysAhead, DateTimeUnit.DAY).atTime(hour, minute).toInstant(zone)
        return if (candidate > now) {
            candidate
        } else {
            // Today is the chosen day but its delivery time has passed (or a DST gap moved the
            // local time backwards past `now`) — the next one is a week out.
            today.plus(daysAhead + DAYS_PER_WEEK, DateTimeUnit.DAY).atTime(hour, minute).toInstant(zone)
        }
    }
}
