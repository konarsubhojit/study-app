package dev.studyflow.core.scheduling

import android.os.SystemClock
import dev.studyflow.core.common.time.ElapsedRealtimeSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Real [ElapsedRealtimeSource], backed by [SystemClock.elapsedRealtime].
 *
 * `:core:common` defines the [ElapsedRealtimeSource] interface but is a plain JVM module (it
 * cannot depend on the Android SDK), and no Android-aware home for a real implementation exists
 * yet — the timer feature that will eventually own [dev.studyflow.core.common.time.AnchoredClock]
 * wiring has not landed. This file is the smallest place to put one for
 * [ReminderActionExecutor]'s "start study session" anchor, and is the sole, narrowly-scoped
 * exclude from the `ForbiddenImport`/`ForbiddenMethodCall` rules for [SystemClock] in
 * `config/detekt/detekt.yml` — every other file in this module is still held to the same rule as
 * the rest of the app.
 */
public object AndroidElapsedRealtimeSource : ElapsedRealtimeSource {
    override fun uptime(): Duration = SystemClock.elapsedRealtime().milliseconds
}
