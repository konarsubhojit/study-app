package dev.studyflow.feature.settings

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

public interface BatteryDiagnosticsSource {
    public fun snapshot(): BatteryDiagnosticsSnapshot

    public fun batterySettingsIntent(): Intent

    public fun appSettingsIntent(): Intent
}

public data class BatteryDiagnosticsSnapshot(
    val batteryOptimised: Boolean,
    val standbyBucket: StandbyBucket,
    val manufacturer: String,
) {
    public val needsAttention: Boolean
        get() = batteryOptimised || standbyBucket == StandbyBucket.RESTRICTED
}

public enum class StandbyBucket {
    ACTIVE,
    WORKING_SET,
    FREQUENT,
    RARE,
    RESTRICTED,
    UNKNOWN,
}

public class AndroidBatteryDiagnosticsSource(
    private val context: Context,
    private val powerManager: PowerManager = context.getSystemService(PowerManager::class.java),
    private val usageStatsManager: UsageStatsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(UsageStatsManager::class.java)
        } else {
            null
        },
) : BatteryDiagnosticsSource {
    override fun snapshot(): BatteryDiagnosticsSnapshot =
        BatteryDiagnosticsSnapshot(
            batteryOptimised = !powerManager.isIgnoringBatteryOptimizations(context.packageName),
            standbyBucket = standbyBucket(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
        )

    override fun batterySettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun standbyBucket(): StandbyBucket =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            when (usageStatsManager?.appStandbyBucket) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> StandbyBucket.ACTIVE
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> StandbyBucket.WORKING_SET
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> StandbyBucket.FREQUENT
                UsageStatsManager.STANDBY_BUCKET_RARE -> StandbyBucket.RARE
                UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> StandbyBucket.RESTRICTED
                else -> StandbyBucket.UNKNOWN
            }
        } else {
            StandbyBucket.UNKNOWN
        }
}

internal fun BatteryDiagnosticsSnapshot.impactText(): String =
    when {
        standbyBucket == StandbyBucket.RESTRICTED && batteryOptimised -> {
            "Android is both restricting StudyFlow in the background and optimising its battery use. " +
                "Exact reminders should still be re-armed, but gentle reminders may be delayed by hours."
        }

        standbyBucket == StandbyBucket.RESTRICTED -> {
            "Android has put StudyFlow in the restricted standby bucket. Background reminder work may " +
                "wait until you open the app again."
        }

        batteryOptimised -> {
            "Battery optimisation is on. Alarm-style reminders are the most reliable, but gentle " +
                "reminders may be delayed while the phone is idle."
        }

        else -> "No battery restriction detected. Reminder reliability looks normal on this device."
    }

internal fun BatteryDiagnosticsSnapshot.oemGuidance(): String =
    when (manufacturer.lowercase()) {
        "xiaomi", "redmi", "poco" -> {
            "On Xiaomi, Redmi and Poco phones, also check Security > Battery > App battery saver and set StudyFlow to No restrictions."
        }

        "oppo", "realme", "oneplus" -> {
            "On Oppo, Realme and OnePlus phones, also allow background activity for StudyFlow in Battery settings."
        }

        "samsung" -> {
            "On Samsung phones, also remove StudyFlow from Sleeping apps and Deep sleeping apps."
        }

        "huawei", "honor" -> {
            "On Huawei and Honor phones, also allow StudyFlow to launch and run in the background."
        }

        else -> "If your phone has an extra battery manager, allow StudyFlow to run in the background."
    }
