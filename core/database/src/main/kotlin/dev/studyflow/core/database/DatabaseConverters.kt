package dev.studyflow.core.database

import androidx.room.TypeConverter
import dev.studyflow.core.database.entity.MaterialSyncState
import dev.studyflow.core.database.entity.RecurrenceEndType
import dev.studyflow.core.database.entity.ReminderTriggerType
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.ReminderPrecision
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.TaskPriority
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Stable scalar representations used by Room.
 *
 * Absolute instants are UTC epoch milliseconds. Local due times intentionally remain ISO local
 * date-times plus a separate IANA zone: converting those values to an instant would change their
 * meaning when the user travels or daylight-saving rules change. Room requires converter methods
 * on registered classes; these small, stateless scalar pairs intentionally form one schema codec.
 */
@Suppress("TooManyFunctions")
public class DatabaseConverters {
    @TypeConverter
    public fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilliseconds()

    @TypeConverter
    public fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::fromEpochMilliseconds)

    @TypeConverter
    public fun durationToMillis(value: Duration?): Long? = value?.inWholeMilliseconds

    @TypeConverter
    public fun millisToDuration(value: Long?): Duration? = value?.milliseconds

    @TypeConverter
    public fun localDateTimeToIso(value: LocalDateTime?): String? = value?.toString()

    @TypeConverter
    public fun isoToLocalDateTime(value: String?): LocalDateTime? = value?.let(LocalDateTime::parse)

    @TypeConverter
    public fun localDateToIso(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    public fun isoToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    public fun timeZoneToId(value: TimeZone?): String? = value?.id

    @TypeConverter
    public fun idToTimeZone(value: String?): TimeZone? = value?.let(TimeZone::of)

    @TypeConverter
    public fun bootIdToString(value: BootId?): String? = value?.value

    @TypeConverter
    public fun stringToBootId(value: String?): BootId? = value?.let(::BootId)

    @TypeConverter
    public fun contentHashToString(value: ContentHash?): String? = value?.hex

    @TypeConverter
    public fun stringToContentHash(value: String?): ContentHash? = value?.let(::ContentHash)

    @TypeConverter
    public fun eventTypeToString(value: SessionEventType?): String? = value?.name

    @TypeConverter
    public fun stringToEventType(value: String?): SessionEventType? = value?.let(SessionEventType::valueOf)

    @TypeConverter
    public fun sessionStatusToString(value: SessionStatus?): String? = value?.name

    @TypeConverter
    public fun stringToSessionStatus(value: String?): SessionStatus? = value?.let(SessionStatus::valueOf)

    @TypeConverter
    public fun reminderPrecisionToString(value: ReminderPrecision?): String? = value?.name

    @TypeConverter
    public fun stringToReminderPrecision(value: String?): ReminderPrecision? = value?.let(ReminderPrecision::valueOf)

    @TypeConverter
    public fun taskPriorityToString(value: TaskPriority?): String? = value?.name

    @TypeConverter
    public fun stringToTaskPriority(value: String?): TaskPriority? = value?.let(TaskPriority::valueOf)

    @TypeConverter
    public fun reminderTriggerTypeToString(value: ReminderTriggerType?): String? = value?.name

    @TypeConverter
    public fun stringToReminderTriggerType(value: String?): ReminderTriggerType? =
        value?.let(ReminderTriggerType::valueOf)

    @TypeConverter
    public fun recurrenceFrequencyToString(value: RecurrenceFrequency?): String? = value?.name

    @TypeConverter
    public fun stringToRecurrenceFrequency(value: String?): RecurrenceFrequency? =
        value?.let(RecurrenceFrequency::valueOf)

    @TypeConverter
    public fun recurrenceEndTypeToString(value: RecurrenceEndType?): String? = value?.name

    @TypeConverter
    public fun stringToRecurrenceEndType(value: String?): RecurrenceEndType? = value?.let(RecurrenceEndType::valueOf)

    @TypeConverter
    public fun materialSyncStateToString(value: MaterialSyncState?): String? = value?.name

    @TypeConverter
    public fun stringToMaterialSyncState(value: String?): MaterialSyncState? = value?.let(MaterialSyncState::valueOf)

    @TypeConverter
    public fun daysOfWeekToString(value: Set<DayOfWeek>?): String? =
        value?.sortedBy(DayOfWeek::ordinal)?.joinToString(separator = ",", transform = DayOfWeek::name)

    @TypeConverter
    public fun stringToDaysOfWeek(value: String?): Set<DayOfWeek>? =
        value
            ?.takeIf(String::isNotEmpty)
            ?.split(',')
            ?.mapTo(linkedSetOf(), DayOfWeek::valueOf)
            ?: value?.let { emptySet() }

    // Exception dates are a short, unordered list read only with the row that owns them, so they
    // live in that row rather than in a table nothing ever joins to.
    @TypeConverter
    public fun localDatesToString(value: Set<LocalDate>?): String? =
        value?.sorted()?.joinToString(separator = ",", transform = LocalDate::toString)

    @TypeConverter
    public fun stringToLocalDates(value: String?): Set<LocalDate>? =
        value
            ?.takeIf(String::isNotEmpty)
            ?.split(',')
            ?.mapTo(linkedSetOf(), LocalDate::parse)
            ?: value?.let { emptySet() }
}
