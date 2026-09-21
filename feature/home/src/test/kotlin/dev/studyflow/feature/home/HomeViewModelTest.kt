package dev.studyflow.feature.home

import androidx.lifecycle.SavedStateHandle
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.data.FakeSessionRepository
import dev.studyflow.core.testing.data.FakeTaskRepository
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import dev.studyflow.core.testing.data.testMaterial
import dev.studyflow.core.testing.data.testStudySession
import dev.studyflow.core.testing.data.testStudyTask
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    @Test
    fun `next due task is the earliest open dated task`() =
        runTest(mainDispatcher.dispatcher) {
            val tasks = FakeTaskRepository()
            tasks.saveAll(
                listOf(
                    testStudyTask(id = "later", dueAt = LocalDateTime(2026, 3, 1, 17, 0)),
                    testStudyTask(id = "next", dueAt = LocalDateTime(2026, 3, 1, 10, 0)),
                    testStudyTask(
                        id = "complete",
                        dueAt = LocalDateTime(2026, 3, 1, 9, 0),
                        completedAt = TEST_WALL_CLOCK,
                    ),
                ),
            )

            val viewModel =
                HomeViewModel(
                    savedStateHandle = SavedStateHandle(),
                    sessionRepository = FakeSessionRepository(),
                    taskRepository = tasks,
                    materialRepository = FakeMaterialRepository(),
                    clock = Clock { TEST_WALL_CLOCK },
                    timeZoneProvider = TimeZoneProvider { TimeZone.UTC },
                )
            advanceUntilIdle()

            assertEquals(
                "next",
                viewModel.state.value.nextTask
                    ?.id,
            )
        }

    @Test
    fun `focus time and streak use cached sessions`() =
        runTest(mainDispatcher.dispatcher) {
            val sessions = FakeSessionRepository()
            sessions.setSessions(
                listOf(
                    testStudySession(
                        id = "today",
                        startedAt = TEST_WALL_CLOCK,
                        endedAt = TEST_WALL_CLOCK,
                        status = SessionStatus.STOPPED,
                        elapsed = SessionElapsed(35.minutes),
                    ),
                    testStudySession(
                        id = "yesterday",
                        startedAt = Instant.parse("2026-02-28T09:00:00Z"),
                        endedAt = Instant.parse("2026-02-28T09:30:00Z"),
                        status = SessionStatus.STOPPED,
                        elapsed = SessionElapsed(30.minutes),
                    ),
                ),
            )
            val viewModel =
                HomeViewModel(
                    SavedStateHandle(),
                    sessions,
                    FakeTaskRepository(),
                    FakeMaterialRepository(),
                    Clock { TEST_WALL_CLOCK },
                    TimeZoneProvider { TimeZone.UTC },
                )
            advanceUntilIdle()

            assertEquals(35.minutes, viewModel.state.value.focusTime)
            assertEquals(2, viewModel.state.value.streakDays)
        }

    @Test
    fun `streak remains visible before the first session today`() =
        runTest(mainDispatcher.dispatcher) {
            val sessions = FakeSessionRepository()
            sessions.setSessions(
                listOf(
                    testStudySession(
                        id = "yesterday",
                        startedAt = Instant.parse("2026-02-28T09:00:00Z"),
                        endedAt = Instant.parse("2026-02-28T09:30:00Z"),
                        status = SessionStatus.STOPPED,
                        elapsed = SessionElapsed(30.minutes),
                    ),
                ),
            )
            val viewModel =
                HomeViewModel(
                    SavedStateHandle(),
                    sessions,
                    FakeTaskRepository(),
                    FakeMaterialRepository(),
                    Clock { TEST_WALL_CLOCK },
                    TimeZoneProvider { TimeZone.UTC },
                )
            advanceUntilIdle()

            assertEquals(1, viewModel.state.value.streakDays)
        }

    @Test
    fun `streak keeps a run through one missed day`() =
        runTest(mainDispatcher.dispatcher) {
            val sessions = FakeSessionRepository()
            sessions.setSessions(
                listOf(
                    testStudySession(
                        id = "friday",
                        startedAt = Instant.parse("2026-02-27T09:00:00Z"),
                        endedAt = Instant.parse("2026-02-27T09:30:00Z"),
                        status = SessionStatus.STOPPED,
                        elapsed = SessionElapsed(30.minutes),
                    ),
                    testStudySession(
                        id = "sunday",
                        startedAt = TEST_WALL_CLOCK,
                        endedAt = TEST_WALL_CLOCK,
                        status = SessionStatus.STOPPED,
                        elapsed = SessionElapsed(30.minutes),
                    ),
                ),
            )
            val viewModel =
                HomeViewModel(
                    SavedStateHandle(),
                    sessions,
                    FakeTaskRepository(),
                    FakeMaterialRepository(),
                    Clock { TEST_WALL_CLOCK },
                    TimeZoneProvider { TimeZone.UTC },
                )
            advanceUntilIdle()

            assertEquals(2, viewModel.state.value.streakDays)
        }

    @Test
    fun `active session and three newest non-deleted materials are surfaced`() =
        runTest(mainDispatcher.dispatcher) {
            val sessions = FakeSessionRepository()
            val active = testStudySession(id = "active")
            sessions.setSessions(listOf(active))
            val materials = FakeMaterialRepository()
            (1..4).forEach { index ->
                materials.save(testMaterial(id = "material-$index", createdAt = TEST_WALL_CLOCK + index.minutes))
            }
            materials.save(testMaterial(id = "deleted").copy(deleted = true))

            val viewModel =
                HomeViewModel(
                    SavedStateHandle(),
                    sessions,
                    FakeTaskRepository(),
                    materials,
                    Clock { TEST_WALL_CLOCK },
                    TimeZoneProvider { TimeZone.UTC },
                )
            advanceUntilIdle()

            assertEquals(active, viewModel.state.value.activeSession)
            assertEquals(
                listOf("material-4", "material-3", "material-2"),
                viewModel.state.value.recentMaterials
                    .map { it.id },
            )
        }
}
