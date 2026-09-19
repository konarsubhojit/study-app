package dev.studyflow.feature.insights

import dev.studyflow.core.domain.stats.WeeklySummary
import dev.studyflow.core.model.Subject
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState

/**
 * The full weekly recap the digest notification links to (issue #63).
 *
 * [summary] is null only until the first aggregate read returns; the screen shows a loading state
 * rather than an empty recap, because "nothing yet" and "you studied nothing" are different
 * answers and rendering the second for the first would be a lie.
 */
public data class WeeklySummaryUiState(
    val summary: WeeklySummary? = null,
    val subjects: List<Subject> = emptyList(),
) : UiState {
    /** Resolves a subject id to the name shown in the recap, matching the statistics screen. */
    public fun subjectName(subjectId: String?): String =
        subjectId?.let { id -> subjects.firstOrNull { it.id == id }?.name } ?: "No subject"
}

public sealed interface WeeklySummaryUiEvent : UiEvent {
    /** The user asked for the shareable card. */
    public data object ShareRequested : WeeklySummaryUiEvent
}

public sealed interface WeeklySummaryUiEffect : UiEffect {
    /** The recap as plain text, ready to hand to a share sheet. */
    public data class ShareSummary(
        val text: String,
    ) : WeeklySummaryUiEffect
}
