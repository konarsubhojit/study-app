package dev.studyflow.feature.home

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.tasks.TaskRepository
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The locally-cached data rendered by the dashboard without a network request. */
public data class HomeUiState(
    val focusTime: Duration = Duration.ZERO,
    val focusGoal: Duration = DEFAULT_FOCUS_GOAL,
    val streakDays: Int = 0,
    val activeSession: StudySession? = null,
    val nextTask: StudyTask? = null,
    val recentMaterials: List<Material> = emptyList(),
) : UiState {
    public companion object {
        public val DEFAULT_FOCUS_GOAL: Duration = 60.minutes
    }
}

public sealed interface HomeUiEvent : UiEvent

@HiltViewModel
public class HomeViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        sessionRepository: SessionRepository,
        taskRepository: TaskRepository,
        materialRepository: MaterialRepository,
        private val clock: Clock,
        private val timeZoneProvider: TimeZoneProvider,
    ) : MviViewModel<HomeUiEvent, Nothing>(savedStateHandle) {
        public val state: StateFlow<HomeUiState> =
            combine(
                sessionRepository.observeSessions(),
                sessionRepository.observeActiveSession(),
                taskRepository.observeTasks(),
                materialRepository.observeAll(),
            ) { sessions, activeSession, tasks, materials ->
                HomeUiState(
                    focusTime = sessions.focusTimeToday(),
                    streakDays = sessions.streakDays(),
                    activeSession = activeSession,
                    nextTask =
                        tasks
                            .filter { !it.deleted && !it.isCompleted && it.dueAt != null }
                            .minByOrNull { requireNotNull(it.dueAtUtc) },
                    recentMaterials = materials.filterNot(Material::deleted).take(3),
                )
            }.stateInViewModel(HomeUiState())

        private fun List<StudySession>.focusTimeToday(): Duration {
            val zone = timeZoneProvider.current()
            val today = clock.now().toLocalDateTime(zone).date
            return filter { !it.deleted && it.startedAt.toLocalDateTime(zone).date == today }
                .fold(Duration.ZERO) { total, session -> total + session.elapsed.counted }
        }

        private fun List<StudySession>.streakDays(): Int {
            val zone = timeZoneProvider.current()
            val studiedDays =
                filter { !it.deleted && it.elapsed.counted > Duration.ZERO }
                    .map { it.startedAt.toLocalDateTime(zone).date }
                    .toSet()
            var day = clock.now().toLocalDateTime(zone).date
            var streak = 0
            while (day in studiedDays) {
                streak++
                day = day.minus(1, DateTimeUnit.DAY)
            }
            return streak
        }

        override fun onEvent(event: HomeUiEvent) = Unit
    }
