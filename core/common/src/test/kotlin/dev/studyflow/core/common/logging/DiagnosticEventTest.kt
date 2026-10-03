package dev.studyflow.core.common.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

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

    /**
     * The PII guarantee, asserted as the property it actually is.
     *
     * Nothing here filters a string — the point is that a call site has no overload to hand a
     * string to, so `put(key, userEmail)` does not compile. A regex would be a filter that fails
     * open the first time someone logs a value its author never imagined; this fails closed,
     * because the only escape is to add an overload in this module and be reviewed for it.
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
