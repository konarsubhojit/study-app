package dev.studyflow.app.timer

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.domain.timer.TimerAccuracyReporter
import dev.studyflow.core.domain.timer.TimerAccuracySample
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class LoggingTimerAccuracyReporter
    @Inject
    constructor(
        private val logger: AppLogger,
    ) : TimerAccuracyReporter {
        override fun report(sample: TimerAccuracySample) {
            logger.info(
                TAG,
                "source=${sample.source} session=${sample.sessionId} " +
                    "expected_ms=${sample.expectedElapsed.inWholeMilliseconds} " +
                    "measured_ms=${sample.measuredElapsed.inWholeMilliseconds} " +
                    "drift_ms=${sample.drift.inWholeMilliseconds}",
            )
        }

        private companion object {
            const val TAG = "TimerAccuracy"
        }
    }
