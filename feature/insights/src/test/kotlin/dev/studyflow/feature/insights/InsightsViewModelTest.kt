package dev.studyflow.feature.insights

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.data.FakeStatsRepository
import dev.studyflow.core.testing.data.FakeSubjectRepository
import dev.studyflow.core.testing.data.testStudySession
import dev.studyflow.core.testing.data.testSubject
import dev.studyflow.core.testing.time.FakeDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("InsightsViewModel")
class InsightsViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val device = FakeDevice()
    private val subjectRepository =
        FakeSubjectRepository().apply {
            put(testSubject(id = "math", name = "Math"))
            put(testSubject(id = "history", name = "History"))
        }

    @Test
    fun `defaults to the last 30 days and loads the aggregates for it`() =
        runTest(mainDispatcher.dispatcher) {
            val session =
                stoppedSession("s1", subjectId = "math", start = device.now() - 21.minutes, counted = 20.minutes)
            val viewModel = viewModel(FakeStatsRepository(utcZone(), seed = listOf(session)))

            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(RangePreset.LAST_30_DAYS, state.rangePreset)
            assertEquals(1, state.subjectTotals.size)
            assertEquals(20.minutes, state.subjectTotals.single().totalCounted)
        }

    @Test
    fun `changing the subject filter re-queries every chart`() =
        runTest(mainDispatcher.dispatcher) {
            val math =
                stoppedSession("s-math", subjectId = "math", start = device.now() - 40.minutes, counted = 20.minutes)
            val history =
                stoppedSession(
                    "s-history",
                    subjectId = "history",
                    start = device.now() - 40.minutes,
                    counted = 10.minutes,
                )
            val viewModel = viewModel(FakeStatsRepository(utcZone(), seed = listOf(math, history)))
            advanceUntilIdle()

            viewModel.onEvent(InsightsUiEvent.SubjectFilterChanged("math"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("math", state.subjectId)
            assertEquals(1, state.subjectTotals.size)
            assertEquals("math", state.subjectTotals.single().subjectId)
        }

    @Test
    fun `changing the range preset widens what is included`() =
        runTest(mainDispatcher.dispatcher) {
            val old = stoppedSession("old", subjectId = "math", start = device.now() - 60.days, counted = 5.minutes)
            val viewModel = viewModel(FakeStatsRepository(utcZone(), seed = listOf(old)))
            advanceUntilIdle()
            assertTrue(
                viewModel.state.value.subjectTotals.isEmpty(),
                "60 days ago is outside the default 30-day range",
            )

            viewModel.onEvent(InsightsUiEvent.RangePresetSelected(RangePreset.LAST_90_DAYS))
            advanceUntilIdle()

            assertEquals(1, viewModel.state.value.subjectTotals.size)
        }

    @Test
    fun `export requested emits the filtered range as CSV`() =
        runTest(mainDispatcher.dispatcher) {
            val session =
                stoppedSession("s1", subjectId = "math", start = device.now() - 40.minutes, counted = 20.minutes)
            val viewModel = viewModel(FakeStatsRepository(utcZone(), seed = listOf(session)))
            advanceUntilIdle()

            viewModel.effects.test {
                viewModel.onEvent(InsightsUiEvent.ExportRequested)
                val csv = (awaitItem() as InsightsUiEffect.ShareCsv).csv
                assertTrue(csv.contains("Math"), "CSV was:\n$csv")
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun utcZone(): TimeZoneProvider = TimeZoneProvider { TimeZone.UTC }

    private fun stoppedSession(
        id: String,
        subjectId: String?,
        start: Instant,
        counted: Duration,
    ) = testStudySession(
        id = id,
        subjectId = subjectId,
        startedAt = start,
        endedAt = start + counted,
        status = SessionStatus.STOPPED,
        elapsed = SessionElapsed(counted = counted),
    )

    private fun viewModel(repository: FakeStatsRepository): InsightsViewModel =
        InsightsViewModel(
            savedStateHandle = SavedStateHandle(),
            statsRepository = repository,
            subjectRepository = subjectRepository,
            clock = device,
        )
}
