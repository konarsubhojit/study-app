package dev.studyflow.feature.insights

import dev.studyflow.core.domain.session.DailySubjectTotal

/**
 * The insights screen's CSV export (issue #60): one row per subject per local calendar day.
 *
 * A pure string builder rather than a platform CSV library: the shape is three plain columns with
 * no embedded newlines to worry about, and RFC 4180 quoting is the one part of "CSV" that is not
 * pure boilerplate, so [escape] is the only place that pulls its weight.
 */
public object InsightsCsv {
    private const val HEADER = "subject,date,duration_minutes"

    /**
     * @param rows the per-subject, per-day totals to export.
     * @param subjectName resolves a subject id to its display name; `null` (no subject) renders as
     *   "No subject" so a row is never silently blank.
     */
    public fun build(
        rows: List<DailySubjectTotal>,
        subjectName: (String?) -> String,
    ): String {
        val lines =
            rows.map { row ->
                "${escape(subjectName(row.subjectId))},${row.day},${row.totalCounted.inWholeMinutes}"
            }
        return (listOf(HEADER) + lines).joinToString(separator = "\n")
    }

    /** Quotes a field per RFC 4180 only when it contains a character that would otherwise break it. */
    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
}
