package dev.studyflow.core.domain.nudges

/**
 * The distinct opt-in nudge types issue #73 allows (each individually toggleable).
 *
 * A closed, small set rather than an open string: every nudge type must be enumerated here to
 * exist at all, which is what makes "every nudge can be disabled in one place" (the master
 * [NudgeSettings.nudgesEnabled] switch [NudgePolicy] checks first) an exhaustive guarantee rather
 * than a convention callers have to remember to follow for a type added later.
 */
public enum class NudgeType {
    /** A quiet end-of-day roll-up of what was studied — never sent more than once per local day. */
    END_OF_DAY_SUMMARY,

    /** "You're N minutes from your goal" — only while genuinely close, never as a countdown. */
    GOAL_ALMOST_REACHED,
}

/**
 * A single nudge [NudgePolicy] has decided is due.
 *
 * Carries no delivery mechanism: turning this into an actual Android notification is
 * `core/notifications`' job (`StudyFlowNotifier`/`NotificationChannels`), kept separate so the
 * *policy* of whether/what to nudge stays a plain, JVM-testable function with no Android
 * dependency.
 */
public data class Nudge(
    val type: NudgeType,
    val message: String,
)

/**
 * All nudge opt-ins, gated by one master switch (issue #73).
 *
 * Defaults are conservative: every field defaults to `false`, matching `settings.proto`'s
 * `nudges_enabled`/`*_enabled` fields (a fresh install sends zero nudges until the user explicitly
 * turns nudges, and then a specific type, on).
 *
 * @property nudgesEnabled the master switch. When `false`, [NudgePolicy.evaluate] returns no
 *   nudges regardless of the per-type toggles below — "easy to turn off entirely" from issue #73.
 */
public data class NudgeSettings(
    val nudgesEnabled: Boolean = false,
    val endOfDaySummaryEnabled: Boolean = false,
    val goalAlmostReachedEnabled: Boolean = false,
) {
    /** Whether [type] may fire at all, given both the master switch and its own toggle. */
    internal fun isEnabled(type: NudgeType): Boolean =
        nudgesEnabled &&
            when (type) {
                NudgeType.END_OF_DAY_SUMMARY -> endOfDaySummaryEnabled
                NudgeType.GOAL_ALMOST_REACHED -> goalAlmostReachedEnabled
            }
}
