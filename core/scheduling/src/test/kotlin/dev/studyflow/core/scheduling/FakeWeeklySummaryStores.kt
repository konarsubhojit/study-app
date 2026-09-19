package dev.studyflow.core.scheduling

import dev.studyflow.core.datastore.WeeklySummaryDeliveryLog
import dev.studyflow.core.datastore.WeeklySummarySchedule
import dev.studyflow.core.datastore.WeeklySummarySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [WeeklySummarySettings], so a scheduling test never touches DataStore. */
class FakeWeeklySummarySettings(
    initial: WeeklySummarySchedule = WeeklySummarySchedule(),
) : WeeklySummarySettings {
    private val state = MutableStateFlow(initial)

    override val schedule: Flow<WeeklySummarySchedule> = state

    fun set(schedule: WeeklySummarySchedule) {
        state.value = schedule
    }

    override suspend fun setEnabled(enabled: Boolean) {
        state.value = state.value.copy(enabled = enabled)
    }

    override suspend fun setDeliveryTime(
        isoDayOfWeek: Int,
        hour: Int,
        minute: Int,
    ) {
        state.value = state.value.copy(isoDayOfWeek = isoDayOfWeek, hour = hour, minute = minute)
    }
}

/** In-memory [WeeklySummaryDeliveryLog]; `-1` is "never delivered", as in the persisted default. */
class FakeWeeklySummaryDeliveryLog(
    private var lastDelivered: Long = -1,
) : WeeklySummaryDeliveryLog {
    override suspend fun lastDeliveredWeekStart(): Long = lastDelivered

    override suspend fun recordDelivered(weekStartEpochDay: Long) {
        lastDelivered = weekStartEpochDay
    }
}
