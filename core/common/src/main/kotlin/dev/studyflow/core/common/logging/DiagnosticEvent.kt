package dev.studyflow.core.common.logging

import java.util.UUID

/**
 * A structured, non-PII diagnostic that survives release sanitisation.
 *
 * Free-text log messages are deliberately discarded in release builds by
 * [LogSanitizer.scrubReleaseMessage]. Structured fields are closed values except for a diagnostic
 * failure message, which must pass [LogSanitizer.sanitizeDiagnosticFailureMessage] first.
 *
 * Codes, keys and ordinary values are closed sets declared in this module. The only free-text
 * exception is a failure message wrapped by [LogSanitizer.sanitizeDiagnosticFailureMessage].
 */
public class DiagnosticEvent internal constructor(
    public val code: DiagnosticCode,
    private val fields: List<Pair<DiagnosticKey, String>>,
) {
    /**
     * The single log line this event becomes, in both debug and release builds.
     *
     * @param throwable appended as its sanitised class name only.
     */
    public fun render(throwable: Throwable? = null): String =
        buildString {
            append("code=")
            append(code.name)
            fields.forEach { (key, value) ->
                append(' ')
                append(key.wireName)
                append('=')
                if (key == DiagnosticKey.ThrowableMessage) {
                    append('"')
                    append(value.replace("\\", "\\\\").replace("\"", "\\\""))
                    append('"')
                } else {
                    append(value)
                }
            }
            LogSanitizer.sanitizeThrowableType(throwable?.javaClass?.simpleName)?.let { type ->
                append(' ')
                append(DiagnosticKey.Throwable.wireName)
                append('=')
                append(type)
            }
        }

    override fun toString(): String = render()
}

/**
 * Every diagnostic this app can emit, with the level and tag it is logged under.
 *
 * A closed set rather than a free string: it is what makes "this line cannot contain user
 * content" a property of the type, and it doubles as the list a reader can grep for when a bug
 * report arrives.
 */
public enum class DiagnosticCode(
    public val level: LogLevel,
    public val tag: String,
) {
    /** A sync run ended before any network call because no account is signed in. */
    SyncSkippedSignedOut(LogLevel.Info, TAG_SYNC),

    /** A sync run failed; the fields say at which stage and whether it is worth retrying. */
    SyncFailed(LogLevel.Warning, TAG_SYNC),

    /** A sync run threw instead of returning an outcome — always a bug, never a transport fault. */
    SyncCrashed(LogLevel.Error, TAG_SYNC),

    /** One inbound row was unreadable and was dropped so the cursor could move past it. */
    SyncInboundRecordDropped(LogLevel.Warning, TAG_SYNC),

    /** A material upload stage started, completed or failed. */
    MaterialUpload(LogLevel.Info, TAG_MATERIALS),

    /** A material download stage started, completed or failed. */
    MaterialDownload(LogLevel.Info, TAG_MATERIALS),

    /** A record-stream sync pass and its cursor progress. */
    RecordSync(LogLevel.Info, TAG_SYNC),

    /** Exact-alarm scheduling degraded to inexact delivery. */
    ReminderDegraded(LogLevel.Warning, TAG_REMINDERS),

    /** Reminder reconciliation found nothing wrong. */
    ReminderIntegrityOk(LogLevel.Info, TAG_REMINDERS),

    /** Reminder reconciliation found missing, overdue or degraded reminders. */
    ReminderIntegrityAnomaly(LogLevel.Warning, TAG_REMINDERS),

    /** The user exported the in-memory log buffer. */
    LogsExported(LogLevel.Info, TAG_LOGS),
}

/**
 * Every key a diagnostic field may use.
 *
 * Closed for the same reason [DiagnosticCode] is: a key is a compile-time constant of this module,
 * so no call site can name a field after something the user typed.
 */
public enum class DiagnosticKey(
    public val wireName: String,
) {
    Trigger("trigger"),
    Stage("stage"),
    Reason("reason"),
    Retryable("retryable"),
    Throwable("throwable"),
    ThrowableMessage("throwableMessage"),
    EntityType("entityType"),
    Pushed("pushed"),
    Applied("applied"),
    Pulled("pulled"),
    CursorAdvanced("cursorAdvanced"),
    Count("count"),
    Outcome("outcome"),
    Part("part"),
    PartCount("partCount"),
    MaterialId("materialId"),
    ReminderId("reminderId"),
    Subsystem("subsystem"),
    Scheduled("scheduled"),
    NotScheduled("notScheduled"),
    Overdue("overdue"),
    Degraded("degraded"),
    NetworkType("networkType"),
    SyncMode("syncMode"),
}

/** Closed subsystem names for exact-alarm degradation events. */
public enum class ReminderDegradationSubsystem {
    REMINDER_SCHEDULING,
    TIMER_INTERVAL,
}

/**
 * Collects the fields of one [DiagnosticEvent].
 *
 * The absence of a `put(key, value: String)` overload is the point of this class: values are
 * restricted to booleans, numbers and enum constants, plus failure details with dedicated,
 * sanitizing APIs.
 */
public class DiagnosticFields internal constructor() {
    private val entries = mutableListOf<Pair<DiagnosticKey, String>>()

    public fun put(
        key: DiagnosticKey,
        value: Boolean,
    ) {
        entries += key to value.toString()
    }

    public fun put(
        key: DiagnosticKey,
        value: Int,
    ) {
        entries += key to value.toString()
    }

    public fun put(
        key: DiagnosticKey,
        value: Long,
    ) {
        entries += key to value.toString()
    }

    public fun put(
        key: DiagnosticKey,
        value: UUID,
    ) {
        entries += key to value.toString()
    }

    public fun put(
        key: DiagnosticKey,
        value: Enum<*>,
    ) {
        entries += key to value.name
    }

    /** Records the *type* of [throwable], never its message, which routinely quotes user content. */
    public fun putThrowableType(
        key: DiagnosticKey,
        throwable: Throwable?,
    ) {
        val type = LogSanitizer.sanitizeThrowableType(throwable?.javaClass?.simpleName) ?: return
        entries += key to type
    }

    public fun putThrowableKind(kind: DiagnosticThrowableKind) {
        entries += DiagnosticKey.Throwable to kind.wireName
    }

    public fun putThrowableMessage(message: SanitizedDiagnosticMessage) {
        if (message.value.isNotBlank()) {
            entries += DiagnosticKey.ThrowableMessage to message.value
        }
    }

    internal fun build(): List<Pair<DiagnosticKey, String>> = entries.toList()
}

/** Stable, source-defined failure kinds that do not depend on throwable class names. */
public enum class DiagnosticThrowableKind(
    public val wireName: String,
) {
    OBJECT_NOT_FOUND("OBJECT_NOT_FOUND"),
    OBJECT_ACCESS_DENIED("OBJECT_ACCESS_DENIED"),
    OBJECT_INTEGRITY("OBJECT_INTEGRITY"),
    OBJECT_QUOTA_EXCEEDED("OBJECT_QUOTA_EXCEEDED"),
    BACKEND_UNREACHABLE("BACKEND_UNREACHABLE"),
    TRANSIENT_STORAGE("TRANSIENT_STORAGE"),
    LOCAL_IO("LOCAL_IO"),
    UNEXPECTED("UNEXPECTED"),
}

/** Builds a [DiagnosticEvent]; the only way to make one. */
public fun diagnosticEvent(
    code: DiagnosticCode,
    fields: DiagnosticFields.() -> Unit = {},
): DiagnosticEvent = DiagnosticEvent(code, DiagnosticFields().apply(fields).build())

/** Logs an exact-alarm fallback using only closed values and an optional opaque reminder UUID. */
public fun AppLogger.reminderDegraded(
    degradation: Enum<*>,
    subsystem: ReminderDegradationSubsystem,
    reminderId: UUID? = null,
    throwable: Throwable? = null,
) {
    diagnostic(
        diagnosticEvent(DiagnosticCode.ReminderDegraded) {
            put(DiagnosticKey.Degraded, degradation)
            put(DiagnosticKey.Subsystem, subsystem)
            reminderId?.let { put(DiagnosticKey.ReminderId, it) }
        },
        throwable,
    )
}

private const val TAG_SYNC = "Sync"
private const val TAG_REMINDERS = "ReminderIntegrity"
private const val TAG_MATERIALS = "Materials"
private const val TAG_LOGS = "Logs"
