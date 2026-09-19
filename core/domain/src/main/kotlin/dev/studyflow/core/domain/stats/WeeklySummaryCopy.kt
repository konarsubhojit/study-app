package dev.studyflow.core.domain.stats

import kotlin.time.Duration

/**
 * The words a weekly recap is made of, in one place (issue #63).
 *
 * The notification, the summary screen and the shareable card all read from here so the three can
 * never disagree about what the week looked like — the acceptance criteria's "content matches the
 * statistics screen exactly" is about the numbers, but a recap that phrased them differently in
 * each surface would still read as three different answers.
 *
 * Subject names are resolved by the caller, through the same `(String?) -> String` shape the CSV
 * export uses: subjects live behind their own repository, and a copy builder has no business
 * loading them.
 */
public object WeeklySummaryCopy {
    private const val MINUTES_PER_HOUR = 60

    /** "4 h 35 min studied", the notification's collapsed line. */
    public fun title(summary: WeeklySummary): String = "${durationLabel(summary.totalCounted)} studied last week"

    /** The expanded body: the dates covered, top subjects, streak, goal progress and the delta. */
    public fun body(
        summary: WeeklySummary,
        subjectName: (String?) -> String,
    ): String = lines(summary, subjectName).joinToString("\n")

    /** Title and body as one block, ready for a share sheet's `EXTRA_TEXT`. */
    public fun shareText(
        summary: WeeklySummary,
        subjectName: (String?) -> String,
    ): String = (listOf(title(summary)) + lines(summary, subjectName)).joinToString("\n")

    /** The range of days the summary covers, as ISO dates. */
    public fun rangeLabel(summary: WeeklySummary): String =
        "${summary.window.start} to ${summary.window.endInclusive}"

    /** "4 h 35 min", or "35 min" below an hour — the same minute resolution the charts use. */
    public fun durationLabel(duration: Duration): String {
        val totalMinutes = duration.inWholeMinutes.coerceAtLeast(0)
        val hours = totalMinutes / MINUTES_PER_HOUR
        val minutes = totalMinutes % MINUTES_PER_HOUR
        return if (hours > 0) "$hours h $minutes min" else "$minutes min"
    }

    /** "up 1 h 10 min on the week before", or "same as the week before" when nothing moved. */
    public fun deltaLabel(summary: WeeklySummary): String {
        val delta = summary.weekOverWeekDelta
        return when {
            delta > Duration.ZERO -> "Up ${durationLabel(delta)} on the week before"
            delta < Duration.ZERO -> "Down ${durationLabel(-delta)} on the week before"
            else -> "Same as the week before"
        }
    }

    /** "62% of your 7 h weekly goal". */
    public fun goalLabel(summary: WeeklySummary): String =
        "${summary.goalAttainmentPercent}% of your ${durationLabel(summary.weeklyGoal)} weekly goal"

    /** "5 day streak", singular-aware because "1 days" is the kind of detail that looks unfinished. */
    public fun streakLabel(summary: WeeklySummary): String =
        when (summary.streakDays) {
            0 -> "No current streak"
            1 -> "1 day streak"
            else -> "${summary.streakDays} day streak"
        }

    /** "Maths 2 h 10 min, History 45 min", or a nudge when nothing was attributed to a subject. */
    public fun topSubjectsLabel(
        summary: WeeklySummary,
        subjectName: (String?) -> String,
    ): String =
        if (summary.topSubjects.isEmpty()) {
            "No subjects recorded"
        } else {
            summary.topSubjects.joinToString(", ") { "${subjectName(it.subjectId)} ${durationLabel(it.totalCounted)}" }
        }

    private fun lines(
        summary: WeeklySummary,
        subjectName: (String?) -> String,
    ): List<String> =
        listOf(
            rangeLabel(summary),
            "Top subjects: ${topSubjectsLabel(summary, subjectName)}",
            streakLabel(summary),
            goalLabel(summary),
            deltaLabel(summary),
        )
}
