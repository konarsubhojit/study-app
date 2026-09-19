package dev.studyflow.core.datastore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Valid ISO-8601 day-of-week numbers, 1 = Monday … 7 = Sunday. */
private val ISO_DAY_OF_WEEK_RANGE = 1..7
private val HOUR_RANGE = 0..23
private val MINUTE_RANGE = 0..59

private const val DEFAULT_ISO_DAY_OF_WEEK = 7
private const val DEFAULT_HOUR = 18

/**
 * When — and whether — the weekly summary is delivered (issue #63).
 *
 * @property isoDayOfWeek ISO-8601 numbering, 1 = Monday … 7 = Sunday, matching
 *   `kotlinx.datetime.DayOfWeek.isoDayNumber`.
 */
public data class WeeklySummarySchedule(
    val enabled: Boolean = false,
    val isoDayOfWeek: Int = DEFAULT_ISO_DAY_OF_WEEK,
    val hour: Int = DEFAULT_HOUR,
    val minute: Int = 0,
) {
    init {
        require(isoDayOfWeek in ISO_DAY_OF_WEEK_RANGE) { "isoDayOfWeek must be 1..7, was $isoDayOfWeek" }
        require(hour in HOUR_RANGE) { "hour must be 0..23, was $hour" }
        require(minute in MINUTE_RANGE) { "minute must be 0..59, was $minute" }
    }
}

/**
 * The narrow slice of [UserSettingsStore] the weekly summary opt-in needs.
 *
 * Follows [AlarmRingtoneSettings]' precedent: a feature module (or a plain unit test, via a fake)
 * reads and writes the schedule without the generated protobuf type on its classpath.
 */
public interface WeeklySummarySettings {
    public val schedule: Flow<WeeklySummarySchedule>

    public suspend fun setEnabled(enabled: Boolean)

    public suspend fun setDeliveryTime(
        isoDayOfWeek: Int,
        hour: Int,
        minute: Int,
    )
}

/**
 * The "has this week's recap already gone out?" bookkeeping the delivery worker keeps.
 *
 * Separate from [WeeklySummarySettings] because it is not a preference: nothing the user sets or
 * sees lives here, and the settings screen has no business being able to write it.
 */
public interface WeeklySummaryDeliveryLog {
    /** Local epoch day of the last delivered week's first day, or `-1` when none ever was. */
    public suspend fun lastDeliveredWeekStart(): Long

    public suspend fun recordDelivered(weekStartEpochDay: Long)
}

/** [WeeklySummaryDeliveryLog] backed by the real, persisted [UserSettingsStore]. */
public class UserSettingsWeeklySummaryDeliveryLog(
    private val store: UserSettingsStore,
) : WeeklySummaryDeliveryLog {
    override suspend fun lastDeliveredWeekStart(): Long = store.data.first().weeklySummaryLastSentWeekStart

    override suspend fun recordDelivered(weekStartEpochDay: Long) {
        store.update { weeklySummaryLastSentWeekStart = weekStartEpochDay }
    }
}

/** [WeeklySummarySettings] backed by the real, persisted [UserSettingsStore]. */
public class UserSettingsWeeklySummarySettings(
    private val store: UserSettingsStore,
) : WeeklySummarySettings {
    override val schedule: Flow<WeeklySummarySchedule> =
        store.data.map { settings ->
            // Out-of-range values can only come from a hand-edited or corrupted file; falling back
            // to the default keeps the schedule usable instead of throwing on every read.
            WeeklySummarySchedule(
                enabled = settings.weeklySummaryEnabled,
                isoDayOfWeek =
                    settings.weeklySummaryDayOfWeek
                        .orDefault(ISO_DAY_OF_WEEK_RANGE, DEFAULT_ISO_DAY_OF_WEEK),
                hour = settings.weeklySummaryHour.orDefault(HOUR_RANGE, DEFAULT_HOUR),
                minute = settings.weeklySummaryMinute.orDefault(MINUTE_RANGE, 0),
            )
        }

    override suspend fun setEnabled(enabled: Boolean) {
        store.update { weeklySummaryEnabled = enabled }
    }

    override suspend fun setDeliveryTime(
        isoDayOfWeek: Int,
        hour: Int,
        minute: Int,
    ) {
        require(isoDayOfWeek in ISO_DAY_OF_WEEK_RANGE) { "isoDayOfWeek must be 1..7, was $isoDayOfWeek" }
        require(hour in HOUR_RANGE) { "hour must be 0..23, was $hour" }
        require(minute in MINUTE_RANGE) { "minute must be 0..59, was $minute" }
        store.update {
            weeklySummaryDayOfWeek = isoDayOfWeek
            weeklySummaryHour = hour
            weeklySummaryMinute = minute
        }
    }

    private fun Int.orDefault(
        valid: IntRange,
        fallback: Int,
    ): Int = if (this in valid) this else fallback
}
