package dev.studyflow.app.logging

import android.util.Log
import dev.studyflow.core.common.logging.LogSanitizer
import timber.log.Timber

object AndroidLogging {
    private var installed = false

    fun install(debug: Boolean) {
        if (!installed) {
            Timber.plant(if (debug) DebugTree() else ReleaseTree())
            installed = true
        }
    }
}

private class DebugTree : Timber.DebugTree() {
    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        super.log(priority, tag, LogSanitizer.scrubDebugMessage(message), t)
    }
}

private class ReleaseTree : Timber.Tree() {
    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        Log.println(
            priority,
            LogSanitizer.releaseTag(),
            LogSanitizer.releaseMessage(),
        )
    }
}
