package dev.studyflow.core.scheduling

import dev.studyflow.core.common.logging.ReminderDegradationSubsystem
import dev.studyflow.core.common.logging.reminderDegraded
import dev.studyflow.core.domain.reminder.ReminderDegradation
import dev.studyflow.core.testing.logging.RecordingAppLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class ReminderDegradationDiagnosticTest {
    @Test
    fun `exact alarm degradation records subsystem and opaque reminder id`() {
        val logger = RecordingAppLogger()
        val reminderId = UUID.fromString("00000000-0000-0000-0000-000000000003")

        logger.reminderDegraded(
            degradation = ReminderDegradation.EXACT_ALARMS_DENIED,
            subsystem = ReminderDegradationSubsystem.REMINDER_SCHEDULING,
            reminderId = reminderId,
            throwable = SecurityException("free text must not be retained"),
        )

        assertEquals(
            listOf(
                "code=ReminderDegraded degraded=EXACT_ALARMS_DENIED " +
                    "subsystem=REMINDER_SCHEDULING reminderId=$reminderId throwable=SecurityException",
            ),
            logger.diagnosticsWith(dev.studyflow.core.common.logging.DiagnosticCode.ReminderDegraded),
        )
    }
}
