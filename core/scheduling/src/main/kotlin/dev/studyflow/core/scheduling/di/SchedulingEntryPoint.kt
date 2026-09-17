package dev.studyflow.core.scheduling.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.scheduling.ReminderActionExecutor
import dev.studyflow.core.scheduling.ReminderDeliveryCoordinator

/**
 * What [ReminderAlarmReceiver][dev.studyflow.core.scheduling.ReminderAlarmReceiver] and
 * [ReminderActionReceiver][dev.studyflow.core.scheduling.ReminderActionReceiver] read from the
 * Hilt graph.
 *
 * A plain `BroadcastReceiver` cannot be `@AndroidEntryPoint` the way an activity or a service can,
 * so it looks its dependencies up through [dagger.hilt.android.EntryPointAccessors] instead — the
 * pattern Hilt documents for exactly this case.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
public interface SchedulingEntryPoint {
    public fun reminderDeliveryCoordinator(): ReminderDeliveryCoordinator

    public fun reminderActionExecutor(): ReminderActionExecutor

    /** So the `goAsync()` coroutine launched by each receiver runs on an injectable dispatcher. */
    public fun dispatcherProvider(): DispatcherProvider
}
