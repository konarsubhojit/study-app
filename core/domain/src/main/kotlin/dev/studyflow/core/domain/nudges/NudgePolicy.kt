package dev.studyflow.core.domain.nudges

import dev.studyflow.core.domain.goals.GoalProgress
import dev.studyflow.core.domain.goals.GoalProgressState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.time.Duration.Companion.minutes

/**
 * Decides *whether and what* to nudge — never *how* to deliver it (issue #73).
 *
 * Kept as a plain function of settings + current state so it is trivially unit-testable and
 * reusable from a `WorkManager` job, a foreground check, or a test, without any of them needing an
 * Android `Context`. Actually posting a notification from the result is `core/notifications`' job.
 *
 * ### No dark patterns
 *
 * There is no streak-loss warning, no "don't break your streak!" message, and no artificial
 * countdown: [GOAL_ALMOST_REACHED][NudgeType.GOAL_ALMOST_REACHED] only fires while genuinely close
 * to a goal already in reach, and [END_OF_DAY_SUMMARY][NudgeType.END_OF_DAY_SUMMARY] is a factual
 * recap, not a prompt to avoid a loss.
 *
 * ### Rate limiting
 *
 * Each [NudgeType] fires at most once per local calendar day, tracked by [lastSentOn] — a map the
 * caller persists (however it likes) and passes back in. This holds even if [evaluate] is called
 * repeatedly within the same day (e.g. on every app foreground), which is what keeps an opt-in
 * feature from ever being able to spam.
 */
public object NudgePolicy {
    /** How close to the goal counts as "almost there" for [NudgeType.GOAL_ALMOST_REACHED]. */
    private val ALMOST_REACHED_THRESHOLD = 15.minutes

    /** The local hour of day at/after which an end-of-day summary is due, if enabled. */
    private const val END_OF_DAY_HOUR = 20

    /**
     * The nudges due right now, in a stable, deterministic order.
     *
     * @param settings the user's opt-ins; [NudgeSettings.nudgesEnabled] being `false` makes this
     *   always return an empty list, before any other check runs.
     * @param now local wall-clock time, in the zone nudges should be evaluated in.
     * @param dailyOverallGoalProgress the current day's overall [GoalProgress], if the user has set
     *   one; `null` disables [NudgeType.GOAL_ALMOST_REACHED] entirely (nothing to be close to).
     * @param lastSentOn the local date each [NudgeType] last fired on, if ever.
     */
    public fun evaluate(
        settings: NudgeSettings,
        now: LocalDateTime,
        dailyOverallGoalProgress: GoalProgress?,
        lastSentOn: Map<NudgeType, LocalDate>,
    ): List<Nudge> {
        if (!settings.nudgesEnabled) return emptyList()

        val today = now.date
        val nudges = mutableListOf<Nudge>()

        if (isDue(NudgeType.GOAL_ALMOST_REACHED, settings, today, lastSentOn) &&
            isAlmostReached(dailyOverallGoalProgress)
        ) {
            val minutesRemaining = dailyOverallGoalProgress?.remaining?.inWholeMinutes
            nudges += Nudge(NudgeType.GOAL_ALMOST_REACHED, "You're $minutesRemaining minutes from today's goal.")
        }

        if (isDue(NudgeType.END_OF_DAY_SUMMARY, settings, today, lastSentOn) && now.hour >= END_OF_DAY_HOUR) {
            val achieved = dailyOverallGoalProgress?.achieved
            val summary = achieved?.let { "You studied ${it.inWholeMinutes} minutes today." }
            nudges += Nudge(type = NudgeType.END_OF_DAY_SUMMARY, message = summary ?: "Here's your day's summary.")
        }

        return nudges
    }

    private fun isDue(
        type: NudgeType,
        settings: NudgeSettings,
        today: LocalDate,
        lastSentOn: Map<NudgeType, LocalDate>,
    ): Boolean = settings.isEnabled(type) && lastSentOn[type] != today

    /** Genuinely close to, but not already at, the goal — never a countdown from further away. */
    private fun isAlmostReached(progress: GoalProgress?): Boolean =
        progress != null && progress.state != GoalProgressState.MET && progress.remaining <= ALMOST_REACHED_THRESHOLD
}
