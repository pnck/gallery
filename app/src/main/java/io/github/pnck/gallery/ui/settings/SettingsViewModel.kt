package io.github.pnck.gallery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.AuthManager
import io.github.pnck.gallery.provider.DeviceAuthChallenge
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsState(
    val googleAuthorized: Boolean = false,
    /** Non-null while a device-flow login is awaiting approval on a second screen. */
    val pendingChallenge: DeviceAuthChallenge? = null,
    val authError: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val googleAuthManager: AuthManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState(googleAuthorized = googleAuthManager.isAuthorized()))
    val state = _state.asStateFlow()

    fun refreshAuthState() {
        _state.value = _state.value.copy(googleAuthorized = googleAuthManager.isAuthorized())
    }

    /**
     * Device flow (ADR-0001): request a code, show it, then poll until the user
     * approves on a second screen. Both calls ride the tunnel.
     */
    fun signInGoogle() {
        viewModelScope.launch {
            when (val challenge = googleAuthManager.requestDeviceAuthorization()) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(pendingChallenge = challenge.data, authError = null)
                    when (val result = googleAuthManager.pollForToken(challenge.data)) {
                        is ApiResult.Success ->
                            _state.value = SettingsState(googleAuthorized = true)
                        is ApiResult.Error ->
                            _state.value = _state.value.copy(pendingChallenge = null, authError = result.message)
                    }
                }
                is ApiResult.Error ->
                    _state.value = _state.value.copy(authError = challenge.message)
            }
        }
    }

    fun cancelPending() {
        _state.value = _state.value.copy(pendingChallenge = null)
    }

    fun signOutGoogle() {
        viewModelScope.launch {
            googleAuthManager.signOut()
            _state.value = SettingsState(googleAuthorized = false)
        }
    }
}
