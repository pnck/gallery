package io.github.pnck.gallery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.network.transport.TransportState
import io.github.pnck.gallery.provider.AuthManager
import io.github.pnck.gallery.provider.DeviceAuthChallenge
import io.github.pnck.gallery.transport.TransportController
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
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

/** One-shot feedback for the "free up space" action. */
sealed interface SettingsEvent {
    data object NothingToFree : SettingsEvent
    data class Freed(val count: Int) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val googleAuthManager: AuthManager,
    private val repo: PhotoRepository,
    private val settings: AppSettingsStore,
    transportController: TransportController,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state = _state.asStateFlow()

    /** The cloud backup folder name (user-configurable, default MyGalleryBackup). */
    val remoteFolderName: StateFlow<String> = settings.remoteFolderName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsStore.DEFAULT_FOLDER_NAME)

    fun updateRemoteFolderName(name: String) {
        viewModelScope.launch { settings.setRemoteFolderName(name) }
    }

    // ── Free up space (T-302, PRD §7.3) ────────────────────────────────────
    private val _freeUris = MutableStateFlow<List<String>?>(null)
    val freeUris: StateFlow<List<String>?> = _freeUris.asStateFlow()

    private val events = Channel<SettingsEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    /** Gather already-synced local copies; hand them to the UI for the delete dialog. */
    fun requestFreeSpace() {
        viewModelScope.launch {
            val uris = repo.freeableLocalUris()
            if (uris.isEmpty()) events.send(SettingsEvent.NothingToFree) else _freeUris.value = uris
        }
    }

    fun onFreeHandled() {
        _freeUris.value = null
    }

    /** After the system delete removed the local files, flip those rows to CLOUD_ONLY. */
    fun confirmFreed(uris: List<String>) {
        viewModelScope.launch {
            repo.releaseLocalCopies(uris)
            events.send(SettingsEvent.Freed(uris.size))
        }
    }

    /** Transport connection state, so Settings can show whether acceleration is up. */
    val transportState: StateFlow<TransportState> =
        transportController.state.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            transportController.state.value,
        )

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
