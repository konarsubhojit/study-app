package dev.studyflow.app.startup

import android.content.Context
import android.os.Trace
import androidx.startup.Initializer
import dev.studyflow.app.BuildConfig
import dev.studyflow.app.logging.AndroidLogging
import dev.studyflow.core.notifications.NotificationChannelRegistrar
import dev.studyflow.core.scheduling.DigestScheduler

class AppStartupInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Channels are registered before anything can post, and deliberately without asking for
        // `POST_NOTIFICATIONS`: creating a channel needs no permission, and the request belongs to
        // a moment of value rather than to cold start (docs/notifications.md).
        NotificationChannelRegistrar(context).register()

        // `KEEP` inside the scheduler means calling this on every cold start is safe: it enqueues
        // the daily digest job once and leaves an already-running one alone (issue #47).
        DigestScheduler(context).ensureScheduled()

        if (BuildConfig.DEBUG) {
            try {
                Trace.beginSection("StudyFlowStartup:${context.packageName}")
                AndroidLogging.install(debug = true)
            } finally {
                Trace.endSection()
            }
        } else {
            AndroidLogging.install(debug = false)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
