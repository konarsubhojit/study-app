package dev.studyflow.app.startup

import android.content.Context
import android.os.Trace
import androidx.startup.Initializer
import dev.studyflow.app.BuildConfig

class AppStartupInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        if (BuildConfig.DEBUG) {
            Trace.beginSection("StudyFlowStartup:${context.packageName}")
            Trace.endSection()
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
