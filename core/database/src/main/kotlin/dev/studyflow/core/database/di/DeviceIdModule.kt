package dev.studyflow.core.database.di

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.time.DeviceIdProvider
import javax.inject.Singleton

/**
 * Supplies the per-install, per-device identifier used to scope sessions to a device.
 *
 * Split out of [DatabaseModule] so it stays under detekt's function-count threshold; the binding
 * itself is unrelated to the database and is only wired here because nothing else in the app
 * provides it yet.
 */
@Module
@InstallIn(SingletonComponent::class)
public object DeviceIdModule {
    @Provides
    @Singleton
    public fun deviceIdProvider(
        @ApplicationContext context: Context,
    ): DeviceIdProvider = DeviceIdProvider { deviceId(context) }

    /** A per-install, per-device identifier; stable without needing a persisted UUID of our own. */
    @SuppressLint("HardwareIds")
    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
}
