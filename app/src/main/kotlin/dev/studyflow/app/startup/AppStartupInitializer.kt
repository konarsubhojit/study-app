package dev.studyflow.app.startup

import android.content.Context
import android.os.Trace
import androidx.startup.Initializer
import dev.studyflow.app.BuildConfig
import dev.studyflow.app.logging.AndroidLogging
import dev.studyflow.core.notifications.NotificationChannelRegistrar

class AppStartupInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Channels are registered before anything can post, and deliberately without asking for
        // `POST_NOTIFICATIONS`: creating a channel needs no permission, and the request belongs to
        // a moment of value rather than to cold start (docs/notifications.md).
        NotificationChannelRegistrar(context).register()

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
