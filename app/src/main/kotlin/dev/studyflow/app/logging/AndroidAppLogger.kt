package dev.studyflow.app.logging

import android.util.Log
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.DiagnosticEvent
import dev.studyflow.core.common.logging.LogBuffer
import dev.studyflow.core.common.logging.LogLevel
import dev.studyflow.core.common.logging.LogSanitizer
import timber.log.Timber
import javax.inject.Inject

/**
 * The Android logger: logcat through Timber, plus the in-memory buffer the user can export.
 *
 * Free text and diagnostics take deliberately different routes. A free-text message goes through
 * Timber, whose release tree scrubs it down to a level and a throwable type; a [DiagnosticEvent]
 * is written straight to [Log] because it is already incapable of carrying user content — its
 * codes, keys and values are all constants of this app's source — and routing it through the
 * release tree would discard the only diagnosis a release build has.
 *
 * The buffer receives the release form in every build type, so an exported log is the same
 * document whether it came from a developer's handset or a user's.
 */
class AndroidAppLogger
    @Inject
    constructor(
        private val buffer: LogBuffer,
    ) : AppLogger {
        override fun log(
            level: LogLevel,
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            Timber.tag(tag).log(level.priority, throwable, message)
            buffer.record(
                level = level,
                tag = LogSanitizer.RELEASE_TAG,
                message = LogSanitizer.scrubReleaseMessage(level, throwable?.javaClass?.simpleName),
            )
        }

        override fun diagnostic(
            event: DiagnosticEvent,
            throwable: Throwable?,
        ) {
            val level = event.code.level
            val rendered = event.render(throwable)
            Log.println(level.priority, LogSanitizer.RELEASE_TAG, rendered)
            buffer.record(level = level, tag = event.code.tag, message = rendered)
        }
    }

private val LogLevel.priority: Int
    get() =
        when (this) {
            LogLevel.Debug -> Log.DEBUG
            LogLevel.Info -> Log.INFO
            LogLevel.Warning -> Log.WARN
            LogLevel.Error -> Log.ERROR
        }
