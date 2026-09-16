package dev.studyflow.core.model

/**
 * A subject or course the user tracks time against.
 *
 * @property colorArgb packed ARGB value. Stored as a plain [Int] so the model stays free of any UI
 *   toolkit type and remains usable from a KMP target.
 */
public data class Subject(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val archived: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Subject.id must not be blank" }
        require(name.isNotBlank()) { "Subject.name must not be blank" }
    }
}
