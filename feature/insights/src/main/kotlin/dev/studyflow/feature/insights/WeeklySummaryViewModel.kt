package dev.studyflow.feature.insights

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.domain.stats.WeeklySummaryCopy
import dev.studyflow.core.domain.stats.WeeklySummaryProvider
import dev.studyflow.core.domain.subjects.SubjectRepository
import dev.studyflow.core.ui.mvi.MviViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * Drives the weekly summary screen (issue #63).
 *
 * Reads the same [WeeklySummaryProvider] the notification does, so the screen behind the
 * notification and the notification itself cannot disagree — and because that provider is built on
 * the insights screen's own aggregates, neither can disagree with the statistics screen.
 */
@HiltViewModel
public class WeeklySummaryViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        summaryProvider: WeeklySummaryProvider,
        subjectRepository: SubjectRepository,
        clock: Clock,
    ) : MviViewModel<WeeklySummaryUiEvent, WeeklySummaryUiEffect>(savedStateHandle) {
        public val state: StateFlow<WeeklySummaryUiState> =
            combine(
                summaryProvider.observe(clock.now()),
                subjectRepository.observeSubjects(),
            ) { summary, subjects ->
                WeeklySummaryUiState(summary = summary, subjects = subjects)
            }.stateInViewModel(WeeklySummaryUiState())

        override fun onEvent(event: WeeklySummaryUiEvent) {
            when (event) {
                WeeklySummaryUiEvent.ShareRequested -> {
                    val current = state.value
                    val summary = current.summary ?: return
                    emitEffect(
                        WeeklySummaryUiEffect.ShareSummary(
                            WeeklySummaryCopy.shareText(summary, current::subjectName),
                        ),
                    )
                }
            }
        }
    }
