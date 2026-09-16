package dev.studyflow.core.testing.quarantine

import org.junit.jupiter.api.Tag

/** JUnit tag carried by [Flaky]; `./gradlew test` excludes it, the slow suite runs only it. */
public const val FLAKY_TAG: String = "flaky"

/**
 * Quarantines a test that is known to fail intermittently.
 *
 * A flaky test poisons the suite: after the second "just re-run it" nobody reads a red build any
 * more, and a real regression sails through. Deleting it or annotating it `@Disabled` is worse —
 * the behaviour it covered is now untested and nothing remembers why.
 *
 * Quarantine is the middle path. The test keeps running, but out of the inner loop and out of the
 * pull-request gate: `./gradlew test` excludes the [FLAKY_TAG] tag, and the nightly slow suite runs
 * exactly those tests so the flake stays visible and measurable. The required [issue] is what makes
 * this a queue rather than a graveyard — a quarantined test has an owner and a way back.
 *
 * @param issue the tracking issue for the flake, for example `#123`. Required.
 * @param reason what is known about why it flakes.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag(FLAKY_TAG)
public annotation class Flaky(
    val issue: String,
    val reason: String = "",
)
