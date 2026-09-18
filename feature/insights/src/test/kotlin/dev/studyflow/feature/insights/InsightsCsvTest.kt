package dev.studyflow.feature.insights

import dev.studyflow.core.domain.session.DailySubjectTotal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

@DisplayName("InsightsCsv")
class InsightsCsvTest {
    @Test
    fun `builds a header plus one row per subject-day total`() {
        val rows =
            listOf(
                DailySubjectTotal(day = "2026-03-01", subjectId = "math", totalCounted = 45.minutes),
                DailySubjectTotal(day = "2026-03-02", subjectId = "history", totalCounted = 30.minutes),
            )

        val csv = InsightsCsv.build(rows) { id -> if (id == "math") "Math" else "History" }

        assertEquals(
            """
            subject,date,duration_minutes
            Math,2026-03-01,45
            History,2026-03-02,30
            """.trimIndent(),
            csv,
        )
    }

    @Test
    fun `a session with no subject is labelled rather than left blank`() {
        val rows = listOf(DailySubjectTotal(day = "2026-03-01", subjectId = null, totalCounted = 10.minutes))

        val csv = InsightsCsv.build(rows) { "No subject" }

        assertEquals("subject,date,duration_minutes\nNo subject,2026-03-01,10", csv)
    }

    @Test
    fun `a subject name containing a comma is quoted per RFC 4180`() {
        val rows = listOf(DailySubjectTotal(day = "2026-03-01", subjectId = "s1", totalCounted = 5.minutes))

        val csv = InsightsCsv.build(rows) { "Physics, Advanced" }

        assertEquals("subject,date,duration_minutes\n\"Physics, Advanced\",2026-03-01,5", csv)
    }

    @Test
    fun `no rows produces only the header`() {
        assertEquals("subject,date,duration_minutes", InsightsCsv.build(emptyList()) { "" })
    }
}
