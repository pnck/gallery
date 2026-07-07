package io.github.pnck.gallery.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.AuthManager
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsState(
    val googleAuthorized: Boolean = false,
    val authError: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val googleAuthManager: AuthManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState(googleAuthorized = googleAuthManager.isAuthorized()))
    val state = _state.asStateFlow()

    /** Refresh on resume — the OAuth exchange completes in OAuthCallbackActivity. */
    fun refreshAuthState() {
        _state.value = _state.value.copy(googleAuthorized = googleAuthManager.isAuthorized())
    }

    fun signInGoogle(context: Context) {
        viewModelScope.launch {
            when (val result = googleAuthManager.startAuthorization(context)) {
                is ApiResult.Success -> _state.value = _state.value.copy(authError = null)
                is ApiResult.Error -> _state.value = _state.value.copy(authError = result.message)
            }
        }
    }

    fun signOutGoogle() {
        viewModelScope.launch {
            googleAuthManager.signOut()
            refreshAuthState()
        }
    }
}
