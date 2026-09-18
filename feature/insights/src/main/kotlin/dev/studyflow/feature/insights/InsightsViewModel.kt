package dev.studyflow.feature.insights

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.domain.stats.AverageSessionLength
import dev.studyflow.core.domain.stats.BucketTotal
import dev.studyflow.core.domain.stats.HourOfDayTotal
import dev.studyflow.core.domain.stats.StatsBucketSize
import dev.studyflow.core.domain.stats.StatsRange
import dev.studyflow.core.domain.stats.StatsRepository
import dev.studyflow.core.domain.stats.SubjectTotal
import dev.studyflow.core.domain.subjects.SubjectRepository
import dev.studyflow.core.model.Subject
import dev.studyflow.core.ui.mvi.MviViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The three inputs every chart on the insights screen reacts to. */
private data class InsightsFilters(
    val preset: RangePreset,
    val range: StatsRange,
    val bucketSize: StatsBucketSize,
    val subjectId: String?,
) {
    companion object {
        fun initial(now: kotlin.time.Instant): InsightsFilters =
            InsightsFilters(
                preset = RangePreset.LAST_30_DAYS,
                range = RangePreset.LAST_30_DAYS.rangeEndingAt(now),
                bucketSize = StatsBucketSize.DAY,
                subjectId = null,
            )
    }
}

/** One combined read of every chart's data for the current [InsightsFilters]. */
private data class StatsSnapshot(
    val bucketTotals: List<BucketTotal>,
    val subjectTotals: List<SubjectTotal>,
    val hourOfDayTotals: List<HourOfDayTotal>,
    val averageSessionLength: AverageSessionLength,
)

/**
 * Drives the insights screen: SQL-backed aggregates re-queried whenever the date range, bucket
 * size or subject filter changes, plus a one-shot CSV export of the currently filtered range
 * (issue #60).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
public class InsightsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val statsRepository: StatsRepository,
        private val subjectRepository: SubjectRepository,
        private val clock: Clock,
    ) : MviViewModel<InsightsUiEvent, InsightsUiEffect>(savedStateHandle) {
        private val filters = MutableStateFlow(InsightsFilters.initial(clock.now()))

        /**
         * Re-subscribes to all four aggregates together whenever any filter changes, so a screen
         * recomposition never sees three charts reflecting the new range and one still showing the
         * old one.
         */
        private val statsSnapshots =
            filters.flatMapLatest { current ->
                combine(
                    statsRepository.observeBucketTotals(current.range, current.bucketSize, current.subjectId),
                    statsRepository.observeSubjectBreakdown(current.range, current.subjectId),
                    statsRepository.observeHourOfDayHeatmap(current.range, current.subjectId),
                    statsRepository.observeAverageSessionLength(current.range, current.subjectId),
                ) { bucketTotals, subjectTotals, hourOfDayTotals, averageSessionLength ->
                    StatsSnapshot(bucketTotals, subjectTotals, hourOfDayTotals, averageSessionLength)
                }
            }

        public val state: StateFlow<InsightsUiState> =
            combine(filters, subjectRepository.observeSubjects(), statsSnapshots, ::toUiState)
                .stateInViewModel(InsightsUiState.initial(clock.now()))

        override fun onEvent(event: InsightsUiEvent) {
            when (event) {
                is InsightsUiEvent.RangePresetSelected -> {
                    filters.value =
                        filters.value.copy(preset = event.preset, range = event.preset.rangeEndingAt(clock.now()))
                }

                is InsightsUiEvent.CustomRangeSelected -> {
                    filters.value = filters.value.copy(range = StatsRange(event.from, event.to))
                }

                is InsightsUiEvent.BucketSizeChanged -> {
                    filters.value = filters.value.copy(bucketSize = event.bucketSize)
                }

                is InsightsUiEvent.SubjectFilterChanged -> {
                    filters.value = filters.value.copy(subjectId = event.subjectId)
                }

                InsightsUiEvent.ExportRequested -> {
                    exportCsv()
                }
            }
        }

        private fun exportCsv() {
            val current = filters.value
            viewModelScope.launch {
                val rows = statsRepository.observeDailySubjectTotals(current.range, current.subjectId).first()
                val subjects = subjectRepository.observeSubjects().first()
                val csv = InsightsCsv.build(rows) { subjectId -> subjects.nameFor(subjectId) }
                emitEffect(InsightsUiEffect.ShareCsv(csv))
            }
        }

        private fun toUiState(
            currentFilters: InsightsFilters,
            subjectOptions: List<Subject>,
            snapshot: StatsSnapshot,
        ): InsightsUiState =
            InsightsUiState(
                rangePreset = currentFilters.preset,
                range = currentFilters.range,
                bucketSize = currentFilters.bucketSize,
                subjectId = currentFilters.subjectId,
                subjectOptions = subjectOptions,
                bucketTotals = snapshot.bucketTotals,
                subjectTotals = snapshot.subjectTotals,
                hourOfDayTotals = snapshot.hourOfDayTotals,
                averageSessionLength = snapshot.averageSessionLength,
            )

        private companion object {
            fun List<Subject>.nameFor(subjectId: String?): String =
                subjectId?.let { id -> firstOrNull { it.id == id }?.name } ?: "No subject"
        }
    }
