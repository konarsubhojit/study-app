package dev.studyflow.app.di

import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.app.BuildConfig
import dev.studyflow.core.network.ApiConfig
import dev.studyflow.feature.settings.LogExportEnvironment
import java.net.URI
import javax.inject.Singleton

/**
 * The build and device facts an exported log needs, gathered where `BuildConfig` actually exists.
 *
 * The API base URL is reduced to its host on purpose: the full URL identifies the backend project
 * and its path may carry a reference nobody intends to publish, while "which backend was this
 * device talking to" is the only part of it a bug report needs.
 */
@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsModule {
    @Provides
    @Singleton
    fun logExportEnvironment(apiConfig: ApiConfig): LogExportEnvironment =
        LogExportEnvironment(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            apiHost = apiConfig.baseUrl.hostOrUnknown(),
        )
}

private fun String.hostOrUnknown(): String = runCatching { URI(this).host }.getOrNull() ?: "unknown"
