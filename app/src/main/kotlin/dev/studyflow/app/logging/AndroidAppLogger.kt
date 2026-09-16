package dev.studyflow.app.logging

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.LogLevel
import timber.log.Timber
import javax.inject.Inject

class AndroidAppLogger
    @Inject
    constructor() : AppLogger {
        override fun log(
            level: LogLevel,
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            Timber.tag(tag).log(level.priority, throwable, message)
        }
    }

private val LogLevel.priority: Int
    get() =
        when (this) {
            LogLevel.Debug -> android.util.Log.DEBUG
            LogLevel.Info -> android.util.Log.INFO
            LogLevel.Warning -> android.util.Log.WARN
            LogLevel.Error -> android.util.Log.ERROR
        }
