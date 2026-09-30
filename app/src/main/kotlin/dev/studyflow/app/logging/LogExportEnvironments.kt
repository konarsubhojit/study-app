package dev.studyflow.app.logging

import android.os.Build
import dev.studyflow.app.BuildConfig
import dev.studyflow.feature.settings.LogExportEnvironment
import java.net.URI

/**
 * The build and device facts an exported log needs, gathered where `BuildConfig` exists.
 *
 * @param apiHost the *host* of the backend this flavour talks to. Never the full base URL: that
 *   identifies the backend project and its path may carry a reference nobody means to publish,
 *   while "which backend was this device talking to" is all a bug report needs from it.
 */
internal fun logExportEnvironment(apiHost: String): LogExportEnvironment =
    LogExportEnvironment(
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        apiHost = apiHost,
    )

/** The host of [url], or a placeholder when it cannot be parsed — never the URL itself. */
internal fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: "unknown"
