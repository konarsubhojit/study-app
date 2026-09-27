package dev.studyflow.feature.auth

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.network.auth.AuthState
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

public data class AccountUiState(
    val authState: AuthState = AuthState.LocalOnly,
    val busy: Boolean = false,
    val outcome: SignInOutcome? = null,
) : UiState

public sealed interface AccountUiEvent : UiEvent {
    public data class SignIn(
        val activity: Activity,
    ) : AccountUiEvent

    public data object SignOut : AccountUiEvent
}

public sealed interface AccountUiEffect : UiEffect

@HiltViewModel
public class AccountViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val session: AuthSession,
        private val coordinator: SignInCoordinator,
        private val credentials: SignInCredentialProvider,
    ) : MviViewModel<AccountUiEvent, AccountUiEffect>(savedStateHandle) {
        private val busy = MutableStateFlow(false)
        private val outcome = MutableStateFlow<SignInOutcome?>(null)
        public val state: StateFlow<AccountUiState> =
            combine(
                session.state,
                busy,
                outcome,
                ::AccountUiState,
            ).stateInViewModel(AccountUiState(session.state.value))

        override fun onEvent(event: AccountUiEvent) {
            if (busy.value) return
            busy.value = true
            when (event) {
                is AccountUiEvent.SignIn -> {
                    viewModelScope.launch {
                        outcome.value = null
                        try {
                            outcome.value =
                                coordinator.signIn { request, google ->
                                    credentials.getCredential(event.activity, request, google)
                                }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: IllegalStateException) {
                            outcome.value = SignInOutcome.Failed
                        } finally {
                            busy.value = false
                        }
                    }
                }

                AccountUiEvent.SignOut -> {
                    viewModelScope.launch {
                        try {
                            session.signOut()
                            outcome.value = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: IllegalStateException) {
                            outcome.value = SignInOutcome.Failed
                        } finally {
                            busy.value = false
                        }
                    }
                }
            }
        }
    }

@Composable
public fun AccountRoute(
    modifier: Modifier = Modifier,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    AccountScreen(
        state = state,
        onSignIn = { activity?.let { viewModel.onEvent(AccountUiEvent.SignIn(it)) } },
        onSignOut = { viewModel.onEvent(AccountUiEvent.SignOut) },
        modifier = modifier,
    )
}

@Composable
public fun AccountScreen(
    state: AccountUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            when (state.authState) {
                AuthState.LocalOnly -> {
                    Text("Local only — study data stays on this device.")
                    TextButton(onClick = onSignIn, enabled = !state.busy) { Text("Sign in with passkey or Google") }
                }

                AuthState.SignedIn -> {
                    Text("Signed in")
                    TextButton(onClick = onSignOut, enabled = !state.busy) { Text("Sign out") }
                }
            }
            if (state.busy) Text("Signing in…")
            val message =
                when (state.outcome) {
                    SignInOutcome.Cancelled -> "Sign-in cancelled. You can continue locally."
                    SignInOutcome.NoCredential -> "No credential is available. Add a Google account and try again."
                    SignInOutcome.NetworkUnavailable -> "Cannot reach the server. Try again when online."
                    SignInOutcome.Rejected -> "Credential rejected. Choose another account or passkey."
                    SignInOutcome.Failed -> "Sign-in could not be completed. Please try again."
                    SignInOutcome.SignedIn, null -> null
                }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
