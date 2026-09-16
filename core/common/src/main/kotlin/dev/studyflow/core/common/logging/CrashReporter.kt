package dev.studyflow.core.common.logging

/** Crash reporting boundary; implementations decide whether an SDK is enabled or opted out. */
public interface CrashReporter {
    public fun initialize(
        enabled: Boolean,
        optedOut: Boolean,
    )

    public fun record(throwable: Throwable)
}
