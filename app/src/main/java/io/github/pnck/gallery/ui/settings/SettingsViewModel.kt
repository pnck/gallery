package io.github.pnck.gallery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.AuthManager
import io.github.pnck.gallery.provider.DeviceAuthChallenge
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The sign-in step, so the UI can show progress / cancel / retry instead of a black hole. */
sealed interface SignInPhase {
    data object Idle : SignInPhase

    /** Requesting the device code — network in flight, nothing to show yet but a spinner. */
    data object Requesting : SignInPhase

    /** Code obtained; waiting for the user to approve on another device. */
    data class AwaitingApproval(val challenge: DeviceAuthChallenge) : SignInPhase

    /** Terminal error. [network] hints that the tunnel may need enabling first. */
    data class Failed(val message: String, val network: Boolean) : SignInPhase
}

data class SettingsState(
    val googleAuthorized: Boolean = false,
    val signIn: SignInPhase = SignInPhase.Idle,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val googleAuthManager: AuthManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state = _state.asStateFlow()

    private var signInJob: Job? = null

    init {
        refreshAuthState()
    }

    /** Keystore-backed read is synchronous + can jank the main thread — do it off-main. */
    fun refreshAuthState() {
        viewModelScope.launch {
            val authorized = withContext(Dispatchers.IO) { googleAuthManager.isAuthorized() }
            _state.value = _state.value.copy(googleAuthorized = authorized)
        }
    }

    /**
     * Device flow (ADR-0001): request a code, show it, then poll until the user
     * approves on a second screen. Both calls ride the shared client (hence the
     * tunnel). Every phase is reflected in [SettingsState.signIn] so the UI always
     * has feedback, and the whole thing is cancellable.
     */
    fun signInGoogle() {
        if (signInJob?.isActive == true) return
        signInJob = viewModelScope.launch {
            _state.value = _state.value.copy(signIn = SignInPhase.Requesting)

            when (val challenge = googleAuthManager.requestDeviceAuthorization()) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(signIn = SignInPhase.AwaitingApproval(challenge.data))
                    when (val result = googleAuthManager.pollForToken(challenge.data)) {
                        is ApiResult.Success ->
                            _state.value = SettingsState(googleAuthorized = true, signIn = SignInPhase.Idle)
                        is ApiResult.Error ->
                            _state.value = _state.value.copy(
                                signIn = SignInPhase.Failed(result.message, network = result.retryable),
                            )
                    }
                }
                is ApiResult.Error ->
                    _state.value = _state.value.copy(
                        signIn = SignInPhase.Failed(challenge.message, network = challenge.retryable),
                    )
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        _state.value = _state.value.copy(signIn = SignInPhase.Idle)
    }

    fun signOutGoogle() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { googleAuthManager.signOut() }
            _state.value = SettingsState(googleAuthorized = false)
        }
    }
}
