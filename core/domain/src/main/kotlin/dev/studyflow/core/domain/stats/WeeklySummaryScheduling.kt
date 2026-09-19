package dev.studyflow.core.domain.stats

/**
 * Re-arms (or cancels) the weekly summary delivery after the user changes their mind (issue #63).
 *
 * A domain interface rather than a direct call into `:core:scheduling` so the settings screen — the
 * only place the opt-in is toggled — can depend on the contract instead of on WorkManager. The
 * single [sync] method reads the persisted preference itself: "turned it off", "moved it to Sunday
 * 18:00" and "nothing changed" are all the same instruction as far as the caller is concerned, and
 * a caller that has to know which one it is eventually gets one of them wrong.
 */
public interface WeeklySummaryScheduling {
    public suspend fun sync()
}
