package dev.studyflow.feature.settings

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.notifications.NotificationChannelStatus
import dev.studyflow.core.notifications.NotificationMessageKey
import dev.studyflow.core.notifications.NotificationMoment
import dev.studyflow.core.notifications.NotificationPermissionAction
import dev.studyflow.core.notifications.NotificationPermissionPolicy
import dev.studyflow.core.notifications.NotificationPermissionState
import dev.studyflow.core.notifications.NotificationPermissionStatus
import dev.studyflow.core.notifications.NotificationSettingsSource
import dev.studyflow.core.notifications.StudyFlowNotificationChannel
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * The in-app notification settings screen, which only ever reports what the system already thinks.
 *
 * Nothing here can turn a channel on: since Android 8 only the user can, from system settings. The
 * value of this screen is that it names what StudyFlow would post, says which of it is currently
 * being suppressed and why, and puts the switch that fixes it one tap away — instead of the user
 * discovering months later that reminders were off all along.
 */
public data class NotificationSettingsUiState(
    val permission: NotificationPermissionState =
        NotificationPermissionState(NotificationPermissionStatus.NOT_REQUESTED, notificationsEnabled = false),
    val channels: List<NotificationChannelStatus> = emptyList(),
    val rationale: NotificationMessageKey? = null,
    val degradation: NotificationMessageKey? = null,
    val loaded: Boolean = false,
) : UiState {
    val notificationsBlocked: Boolean
        get() = !permission.canPost

    /** The permission dialog is still available, so "Turn on" can lead somewhere better than settings. */
    val canRequestPermission: Boolean
        get() =
            permission.status == NotificationPermissionStatus.NOT_REQUESTED ||
                permission.status == NotificationPermissionStatus.DENIED
}

public sealed interface NotificationSettingsUiEvent : UiEvent {
    /**
     * Re-reads system state; sent on every resume, because the user may have just changed it.
     *
     * [shouldShowRationale] is the activity's answer to the platform question of the same name,
     * which the ViewModel cannot ask for itself.
     */
    public data class Refresh(
        val shouldShowRationale: Boolean = false,
    ) : NotificationSettingsUiEvent

    public data object EnableNotifications : NotificationSettingsUiEvent

    public data object RationaleAccepted : NotificationSettingsUiEvent

    public data object RationaleDismissed : NotificationSettingsUiEvent

    /** The system dialog closed. [shouldShowRationale] is re-read afterwards: a refusal that can
     * still be explained is a very different state from a final one. */
    public data class PermissionResult(val shouldShowRationale: Boolean = false) : NotificationSettingsUiEvent

    public data class OpenChannelSettings(
        val channel: StudyFlowNotificationChannel,
    ) : NotificationSettingsUiEvent

    public data object OpenAppSettings : NotificationSettingsUiEvent
}

public sealed interface NotificationSettingsUiEffect : UiEffect {
    /** Ask the system for `POST_NOTIFICATIONS`; only an activity can do this. */
    public data object RequestPermission : NotificationSettingsUiEffect

    public data class OpenSystemSettings(
        val intent: Intent,
        val fallbackIntent: Intent? = null,
    ) : NotificationSettingsUiEffect
}

@HiltViewModel
public class NotificationSettingsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val source: NotificationSettingsSource,
    ) : MviViewModel<NotificationSettingsUiEvent, NotificationSettingsUiEffect>(savedStateHandle) {
        private val systemState = MutableStateFlow(NotificationSettingsUiState())

        // Only the rationale survives process death: it is a decision the user is part way through,
        // whereas everything else must be re-read from the system rather than restored stale.
        private val rationaleChanges = MutableStateFlow<String?>(savedStateHandle[RATIONALE_KEY])
        private val rationale = rationaleChanges.stateInSavedState(RATIONALE_KEY, null)

        public val state: StateFlow<NotificationSettingsUiState> =
            combine(systemState, rationale) { system, rationaleKey ->
                // Resolved by name rather than valueOf: a saved key from a previous app version may
                // no longer exist, and a dropped rationale is better than a crash on restore.
                system.copy(rationale = NotificationMessageKey.entries.firstOrNull { it.name == rationaleKey })
            }.stateInViewModel(systemState.value)

        override fun onEvent(event: NotificationSettingsUiEvent) {
            when (event) {
                is NotificationSettingsUiEvent.Refresh -> {
                    refresh(event.shouldShowRationale)
                }

                NotificationSettingsUiEvent.EnableNotifications -> {
                    enableNotifications()
                }

                NotificationSettingsUiEvent.RationaleAccepted -> {
                    rationaleChanges.value = null
                    requestPermission()
                }

                NotificationSettingsUiEvent.RationaleDismissed -> {
                    rationaleChanges.value = null
                    systemState.value = systemState.value.copy(degradation = MOMENT.degradationKey)
                }

                is NotificationSettingsUiEvent.PermissionResult -> {
                    refresh(event.shouldShowRationale)
                }

                is NotificationSettingsUiEvent.OpenChannelSettings -> {
                    emitEffect(
                        NotificationSettingsUiEffect.OpenSystemSettings(
                            intent = source.channelSettingsIntent(event.channel),
                            fallbackIntent = source.appSettingsIntent(),
                        ),
                    )
                }

                NotificationSettingsUiEvent.OpenAppSettings -> {
                    emitEffect(NotificationSettingsUiEffect.OpenSystemSettings(source.appSettingsIntent()))
                }
            }
        }

        private fun refresh(shouldShowRationale: Boolean = false) {
            val snapshot = source.snapshot(shouldShowRationale)
            systemState.value =
                systemState.value.copy(
                    permission = snapshot.permission,
                    channels = snapshot.channels,
                    degradation = NotificationPermissionPolicy.degradationFor(snapshot.permission, MOMENT),
                    loaded = true,
                )
        }

        private fun enableNotifications() {
            when (val action = NotificationPermissionPolicy.actionFor(systemState.value.permission, MOMENT)) {
                NotificationPermissionAction.RequestPermission -> {
                    requestPermission()
                }

                is NotificationPermissionAction.ShowRationale -> {
                    rationaleChanges.value = action.messageKey.name
                }

                is NotificationPermissionAction.OpenSystemSettings -> {
                    systemState.value = systemState.value.copy(degradation = action.degradationKey)
                    emitEffect(
                        NotificationSettingsUiEffect.OpenSystemSettings(
                            intent =
                                action.channel
                                    ?.let(source::channelSettingsIntent)
                                    ?: source.appSettingsIntent(),
                            fallbackIntent = action.channel?.let { source.appSettingsIntent() },
                        ),
                    )
                }

                NotificationPermissionAction.None -> {
                    refresh()
                }
            }
        }

        private fun requestPermission() {
            // Recorded before the dialog rather than after it: the result callback is not guaranteed to
            // arrive, and an unrecorded request would look like "never asked" forever.
            source.recordPermissionRequested()
            emitEffect(NotificationSettingsUiEffect.RequestPermission)
        }

        private companion object {
            /** The user opened this screen, so talking about notifications is exactly what they expect. */
            val MOMENT = NotificationMoment.NOTIFICATION_SETTINGS
            const val RATIONALE_KEY = "notificationSettings.rationale"
        }
    }
