package dev.studyflow.core.common.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * The release diagnostic contract: rich enough to name a cause, structurally unable to name a user.
 */
class DiagnosticEventTest {
    @Test
    fun `an event renders its code and fields as one line`() {
        val event =
            diagnosticEvent(DiagnosticCode.SyncFailed) {
                put(DiagnosticKey.Stage, Stage.Push)
                put(DiagnosticKey.Retryable, true)
                put(DiagnosticKey.Pushed, 2)
            }

        assertEquals("code=SyncFailed stage=Push retryable=true pushed=2", event.render())
    }

    @Test
    fun `a throwable contributes its sanitized type and nothing else`() {
        val event = diagnosticEvent(DiagnosticCode.SyncCrashed)

        val rendered = event.render(IllegalStateException("upload of /storage/emulated/0/exam.pdf failed"))

        assertEquals("code=SyncCrashed throwable=IllegalStateException", rendered)
        assertFalse(rendered.contains("exam.pdf"))
    }

    @Test
    fun `a sanitized failure message is quoted and remains safe to render`() {
        val event =
            diagnosticEvent(DiagnosticCode.MaterialUpload) {
                putThrowableKind(DiagnosticThrowableKind.TRANSIENT_STORAGE)
                putThrowableMessage(
                    LogSanitizer.sanitizeDiagnosticFailureMessage(
                        "stat failed https://bucket.example/object?signature=signed-value",
                    ),
                )
            }

        val rendered = event.render()

        assertEquals("code=MaterialUpload throwable=TRANSIENT_STORAGE throwableMessage=\"stat failed [url]\"", rendered)
        assertFalse(rendered.contains("signed-value"))
        assertFalse(rendered.contains("?"))
    }

    @Test
    fun `reminder degradation records its closed fields`() {
        var recordedEvent: DiagnosticEvent? = null
        var recordedThrowable: Throwable? = null
        val logger =
            object : AppLogger {
                override fun log(
                    level: LogLevel,
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                ) = Unit

                override fun diagnostic(
                    event: DiagnosticEvent,
                    throwable: Throwable?,
                ) {
                    recordedEvent = event
                    recordedThrowable = throwable
                }
            }
        val reminderId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val failure = IllegalStateException("exact alarm denied")

        logger.reminderDegraded(
            degradation = Stage.Push,
            subsystem = ReminderDegradationSubsystem.TIMER_INTERVAL,
            reminderId = reminderId,
            throwable = failure,
        )

        assertEquals(
            "code=ReminderDegraded degraded=Push subsystem=TIMER_INTERVAL reminderId=$reminderId " +
                "throwable=IllegalStateException",
            recordedEvent?.render(recordedThrowable),
        )
    }

    /**
     * Ordinary diagnostic values stay closed; failure text is the deliberate, separately sanitized
     * exception. This pins that callers cannot pass raw text to a regular field.
     */
    @Test
    fun `no diagnostic field accepts free text`() {
        val textual =
            DiagnosticFields::class.java.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) }
                .filter { method -> method.parameterTypes.any { CharSequence::class.java.isAssignableFrom(it) } }

        assertTrue(
            textual.isEmpty(),
            "DiagnosticFields must not accept text: ${textual.map { it.name }}",
        )
    }

    @Test
    fun `every diagnostic code carries a constant tag and a level`() {
        DiagnosticCode.entries.forEach { code ->
            assertTrue(code.tag.isNotBlank(), "$code has no tag")
            assertTrue(code.level in LogLevel.entries, "$code has no level")
        }
    }

    private enum class Stage { Push, Pull }
}
