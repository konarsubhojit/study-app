package dev.studyflow.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import androidx.datastore.dataStoreFile
import androidx.datastore.migrations.SharedPreferencesMigration
import dev.studyflow.core.datastore.proto.ActiveTimerAnchor
import dev.studyflow.core.datastore.proto.SyncMode
import dev.studyflow.core.datastore.proto.Theme
import dev.studyflow.core.datastore.proto.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val SETTINGS_FILE_NAME = "user_settings.pb"
private const val TIMER_FILE_NAME = "active_timer.pb"
private const val LEGACY_SETTINGS_NAME = "settings"

/** Valid wall-clock ranges for a reminder time; a legacy value outside them is ignored. */
private val REMINDER_HOUR_RANGE = 0..23
private val REMINDER_MINUTE_RANGE = 0..59

/**
 * Settings defaults are encoded in [UserSettings] so a fresh install and a missing legacy key
 * receive the same value without a blocking initialization write.
 */
private val Context.userSettingsDataStore: DataStore<UserSettings> by dataStore(
    fileName = SETTINGS_FILE_NAME,
    serializer = UserSettingsSerializer,
    produceMigrations = ::settingsMigrations,
)

/** Typed, transactional user settings with legacy `settings` SharedPreferences migration. */
public class UserSettingsStore internal constructor(
    private val dataStore: DataStore<UserSettings>,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    /** Emits settings asynchronously; collecting this flow never blocks the main thread. */
    public val data: Flow<UserSettings> = dataStore.data

    /** Atomically applies a settings change. */
    public suspend fun update(transform: UserSettings.Builder.() -> Unit): UserSettings =
        withContext(ioContext) {
            dataStore.updateData { current -> current.toBuilder().apply(transform).build() }
        }
}

/**
 * The narrow slice of [UserSettingsStore] a ringtone picker needs — just enough for a feature
 * module (or a plain unit test, via a fake) to read and persist the chosen alarm sound without
 * depending on the whole settings store or its generated proto type.
 */
public interface AlarmRingtoneSettings {
    /** Empty means "the device's default alarm sound" — see `settings.proto`'s field doc. */
    public val uri: Flow<String>

    public suspend fun setUri(uri: String)
}

/** [AlarmRingtoneSettings] backed by the real, persisted [UserSettingsStore]. */
public class UserSettingsAlarmRingtoneSettings(
    private val store: UserSettingsStore,
) : AlarmRingtoneSettings {
    override val uri: Flow<String> = store.data.map { it.alarmRingtoneUri }

    override suspend fun setUri(uri: String) {
        store.update { alarmRingtoneUri = uri }
    }
}

public data class FocusTimerConfig(
    val focusInterval: Duration,
    val breakInterval: Duration,
    val maximumSessionDuration: Duration,
    val inactivityPromptAfter: Duration,
) {
    init {
        require(focusInterval.isPositive()) { "focusInterval must be positive" }
        require(breakInterval.isPositive()) { "breakInterval must be positive" }
        require(maximumSessionDuration.isPositive()) { "maximumSessionDuration must be positive" }
        require(inactivityPromptAfter.isPositive()) { "inactivityPromptAfter must be positive" }
    }
}

public interface FocusTimerSettings {
    public val config: Flow<FocusTimerConfig>

    public suspend fun setFocusIntervalMinutes(minutes: Int)

    public suspend fun setBreakIntervalMinutes(minutes: Int)

    public suspend fun setMaximumSessionMinutes(minutes: Int)

    public suspend fun setInactivityPromptMinutes(minutes: Int)
}

public class UserSettingsFocusTimerSettings(
    private val store: UserSettingsStore,
) : FocusTimerSettings {
    override val config: Flow<FocusTimerConfig> =
        store.data.map { settings ->
            FocusTimerConfig(
                focusInterval = settings.defaultFocusMinutes.toPositiveMinutes(),
                breakInterval = settings.defaultBreakMinutes.toPositiveMinutes(),
                maximumSessionDuration = settings.maximumSessionMinutes.toPositiveMinutes(),
                inactivityPromptAfter = settings.inactivityPromptMinutes.toPositiveMinutes(),
            )
        }

    override suspend fun setFocusIntervalMinutes(minutes: Int) {
        require(minutes > 0) { "minutes must be positive" }
        store.update { defaultFocusMinutes = minutes }
    }

    override suspend fun setBreakIntervalMinutes(minutes: Int) {
        require(minutes > 0) { "minutes must be positive" }
        store.update { defaultBreakMinutes = minutes }
    }

    override suspend fun setMaximumSessionMinutes(minutes: Int) {
        require(minutes > 0) { "minutes must be positive" }
        store.update { maximumSessionMinutes = minutes }
    }

    override suspend fun setInactivityPromptMinutes(minutes: Int) {
        require(minutes > 0) { "minutes must be positive" }
        store.update { inactivityPromptMinutes = minutes }
    }
}

/**
 * Overall daily/weekly study targets (issue #73).
 *
 * @property dailyGoal `null` means no daily target is set — see `settings.proto`'s
 *   `daily_goal_minutes` doc for why `0` is the "unset" sentinel rather than a separate flag.
 */
public data class GoalTargets(
    val dailyGoal: Duration?,
    val weeklyGoal: Duration?,
)

public interface GoalSettings {
    public val targets: Flow<GoalTargets>

    /** `0` clears the daily target; matches `settings.proto`'s "0 means unset" convention. */
    public suspend fun setDailyGoalMinutes(minutes: Int)

    /** `0` clears the weekly target. */
    public suspend fun setWeeklyGoalMinutes(minutes: Int)
}

public class UserSettingsGoalSettings(
    private val store: UserSettingsStore,
) : GoalSettings {
    override val targets: Flow<GoalTargets> =
        store.data.map { settings ->
            GoalTargets(
                dailyGoal = settings.dailyGoalMinutes.toGoalOrNull(),
                weeklyGoal = settings.weeklyGoalMinutes.toGoalOrNull(),
            )
        }

    override suspend fun setDailyGoalMinutes(minutes: Int) {
        require(minutes >= 0) { "minutes must not be negative" }
        store.update { dailyGoalMinutes = minutes }
    }

    override suspend fun setWeeklyGoalMinutes(minutes: Int) {
        require(minutes >= 0) { "minutes must not be negative" }
        store.update { weeklyGoalMinutes = minutes }
    }
}

/**
 * Opt-in, rate-limited nudge toggles (issue #73). See `settings.proto`'s `nudges_enabled` doc for
 * why every field defaults to `false`.
 *
 * @property nudgesEnabled the single master switch every nudge type is gated behind.
 */
public data class NudgeToggles(
    val nudgesEnabled: Boolean,
    val endOfDaySummaryEnabled: Boolean,
    val goalAlmostReachedEnabled: Boolean,
)

public interface NudgeSettings {
    public val toggles: Flow<NudgeToggles>

    public suspend fun setNudgesEnabled(enabled: Boolean)

    public suspend fun setEndOfDaySummaryEnabled(enabled: Boolean)

    public suspend fun setGoalAlmostReachedEnabled(enabled: Boolean)
}

public class UserSettingsNudgeSettings(
    private val store: UserSettingsStore,
) : NudgeSettings {
    override val toggles: Flow<NudgeToggles> =
        store.data.map { settings ->
            NudgeToggles(
                nudgesEnabled = settings.nudgesEnabled,
                endOfDaySummaryEnabled = settings.endOfDaySummaryEnabled,
                goalAlmostReachedEnabled = settings.goalAlmostReachedEnabled,
            )
        }

    override suspend fun setNudgesEnabled(enabled: Boolean) {
        store.update { nudgesEnabled = enabled }
    }

    override suspend fun setEndOfDaySummaryEnabled(enabled: Boolean) {
        store.update { endOfDaySummaryEnabled = enabled }
    }

    override suspend fun setGoalAlmostReachedEnabled(enabled: Boolean) {
        store.update { goalAlmostReachedEnabled = enabled }
    }
}

/** Minimal anchor retained only while a timer is active, for direct-boot recovery. */
public data class ActiveTimer(
    val sessionId: String,
    val wallClockEpochMillis: Long,
    val uptimeMillis: Long,
    val bootId: String,
)

/** A device-protected, asynchronous store for the active timer anchor. */
public class ActiveTimerStore internal constructor(
    private val dataStore: DataStore<ActiveTimerAnchor>,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    public val activeTimer: Flow<ActiveTimer?> = dataStore.data.map(ActiveTimerAnchor::toActiveTimer)

    /** Replaces the current timer anchor atomically. */
    public suspend fun set(timer: ActiveTimer): Unit =
        withContext(ioContext) {
            require(timer.sessionId.isNotBlank()) { "sessionId must not be blank" }
            require(timer.uptimeMillis >= 0) { "uptimeMillis must not be negative" }
            require(timer.bootId.isNotBlank()) { "bootId must not be blank" }
            dataStore.updateData {
                ActiveTimerAnchor
                    .newBuilder()
                    .setSessionId(timer.sessionId)
                    .setWallClockEpochMs(timer.wallClockEpochMillis)
                    .setUptimeMs(timer.uptimeMillis)
                    .setBootId(timer.bootId)
                    .build()
            }
        }

    /** Clears the anchor once the timer is paused or stopped. */
    public suspend fun clear(): Unit =
        withContext(ioContext) {
            dataStore.updateData { ActiveTimerAnchor.getDefaultInstance() }
        }
}

/** Returns the process-singleton settings store for this application context. */
public fun Context.userSettingsStore(): UserSettingsStore = UserSettingsStore(userSettingsDataStore)

/** Returns the process-singleton, device-protected active-timer store for this application context. */
public fun Context.activeTimerStore(): ActiveTimerStore =
    ActiveTimerStore(DeviceProtectedTimerDataStore.get(applicationContext))

internal object UserSettingsSerializer : Serializer<UserSettings> {
    override val defaultValue: UserSettings = UserSettings.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): UserSettings = UserSettings.parseFrom(input)

    override suspend fun writeTo(
        t: UserSettings,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}

internal object ActiveTimerAnchorSerializer : Serializer<ActiveTimerAnchor> {
    override val defaultValue: ActiveTimerAnchor = ActiveTimerAnchor.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): ActiveTimerAnchor = ActiveTimerAnchor.parseFrom(input)

    override suspend fun writeTo(
        t: ActiveTimerAnchor,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}

private object DeviceProtectedTimerDataStore {
    @Volatile
    private var instance: DataStore<ActiveTimerAnchor>? = null

    fun get(context: Context): DataStore<ActiveTimerAnchor> =
        instance ?: synchronized(this) {
            instance ?: DataStoreFactory
                .create(
                    serializer = ActiveTimerAnchorSerializer,
                    produceFile = {
                        deviceProtectedTimerFile(context)
                    },
                ).also { instance = it }
        }
}

internal fun settingsMigrations(context: Context): List<SharedPreferencesMigration<UserSettings>> =
    listOf(
        SharedPreferencesMigration(
            context = context,
            sharedPreferencesName = LEGACY_SETTINGS_NAME,
        ) { preferences, current ->
            current
                .toBuilder()
                .apply {
                    preferences.getString("theme", null)?.let(::legacyTheme)?.let(::setTheme)
                    preferences
                        .getInt("default_focus_minutes", defaultFocusMinutes)
                        .takeIf { it > 0 }
                        ?.let(::setDefaultFocusMinutes)
                    preferences
                        .getInt("default_break_minutes", defaultBreakMinutes)
                        .takeIf { it > 0 }
                        ?.let(::setDefaultBreakMinutes)
                    if (preferences.contains("reminders_enabled")) {
                        setRemindersEnabled(preferences.getBoolean("reminders_enabled", remindersEnabled))
                    }
                    preferences
                        .getInt("reminder_hour", reminderHour)
                        .takeIf { it in REMINDER_HOUR_RANGE }
                        ?.let(::setReminderHour)
                    preferences
                        .getInt("reminder_minute", reminderMinute)
                        .takeIf { it in REMINDER_MINUTE_RANGE }
                        ?.let(::setReminderMinute)
                    preferences
                        .getLong("storage_quota_bytes", storageQuotaBytes)
                        .takeIf { it > 0 }
                        ?.let(::setStorageQuotaBytes)
                    preferences.getString("sync_mode", null)?.let(::legacySyncMode)?.let(::setSyncMode)
                }.build()
        },
    )

internal fun deviceProtectedTimerFile(context: Context) =
    context.createDeviceProtectedStorageContext().dataStoreFile(TIMER_FILE_NAME)

private fun legacyTheme(value: String): Theme? =
    when (value.lowercase()) {
        "system" -> Theme.THEME_SYSTEM
        "light" -> Theme.THEME_LIGHT
        "dark" -> Theme.THEME_DARK
        else -> null
    }

private fun legacySyncMode(value: String): SyncMode? =
    when (value.lowercase()) {
        "wifi_only" -> SyncMode.SYNC_MODE_WIFI_ONLY
        "any_network" -> SyncMode.SYNC_MODE_ANY_NETWORK
        "manual" -> SyncMode.SYNC_MODE_MANUAL
        else -> null
    }

private fun ActiveTimerAnchor.toActiveTimer(): ActiveTimer? =
    if (sessionId.isBlank() || bootId.isBlank() || uptimeMs < 0) {
        null
    } else {
        ActiveTimer(
            sessionId = sessionId,
            wallClockEpochMillis = wallClockEpochMs,
            uptimeMillis = uptimeMs,
            bootId = bootId,
        )
    }

private fun Int.toPositiveMinutes(): Duration = coerceAtLeast(1).minutes

/** `0` means "no goal set" (see `settings.proto`); any other value is that many minutes. */
private fun Int.toGoalOrNull(): Duration? = if (this <= 0) null else minutes
