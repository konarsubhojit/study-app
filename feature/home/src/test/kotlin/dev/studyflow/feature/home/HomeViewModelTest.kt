package dev.studyflow.feature.home

import androidx.lifecycle.SavedStateHandle
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.data.FakeSessionRepository
import dev.studyflow.core.testing.data.FakeTaskRepository
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import dev.studyflow.core.testing.data.testStudyTask
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

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
                    testStudyTask(id = "complete", dueAt = LocalDateTime(2026, 3, 1, 9, 0), completedAt = TEST_WALL_CLOCK),
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

            assertEquals("next", viewModel.state.value.nextTask?.id)
        }
}
