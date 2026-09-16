package dev.studyflow.app.startup

import android.content.Context
import android.os.Trace
import androidx.startup.Initializer
import dev.studyflow.app.BuildConfig
import dev.studyflow.app.logging.AndroidLogging

class AppStartupInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        if (BuildConfig.DEBUG) {
            try {
                Trace.beginSection("StudyFlowStartup:${context.packageName}")
                AndroidLogging.install(debug = true)
            } finally {
                Trace.endSection()
            }
        } else {
            AndroidLogging.install(debug = BuildConfig.DEBUG)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
