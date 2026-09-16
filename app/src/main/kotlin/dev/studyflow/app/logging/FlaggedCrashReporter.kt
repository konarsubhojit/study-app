package dev.studyflow.app.logging

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.CrashReporter
import javax.inject.Inject

class FlaggedCrashReporter
    @Inject
    constructor(
        private val logger: AppLogger,
    ) : CrashReporter {
        private var active = false

        override fun initialize(
            enabled: Boolean,
            optedOut: Boolean,
        ) {
            active = enabled && !optedOut
            if (active) {
                logger.info("CrashReporter", "Crash reporting enabled")
            }
        }

        override fun record(throwable: Throwable) {
            if (active) {
                logger.error("CrashReporter", "Unhandled exception", throwable)
            }
        }
    }
