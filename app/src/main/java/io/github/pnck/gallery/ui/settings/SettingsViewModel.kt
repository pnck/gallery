package io.github.pnck.gallery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import coil3.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.data.sync.AccountSwitchGuard
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.network.transport.TransportState
import io.github.pnck.gallery.provider.AuthManager
import io.github.pnck.gallery.provider.DeviceAuthChallenge
import io.github.pnck.gallery.provider.ICloudStorageProvider
import io.github.pnck.gallery.transport.TransportController
import io.github.pnck.gallery.work.SyncPipeline
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** Email of the signed-in account, so the user can spot an account mismatch. */
    val accountEmail: String? = null,
    /**
     * Whether the cloud is actually REACHABLE with the current grant — derived
     * from a live `about()` probe, never from token existence. A held token with
     * a dead tunnel is NOT "connected" (owner report: the panel showed Connected
     * while nothing could reach Drive). null = probe in flight / not signed in.
     */
    val cloudReachable: Boolean? = null,
    /** The separate drive.readonly grant ("My Drive" browser) — managed here too. */
    val myDriveAuthorized: Boolean = false,
    /** Live probe of the My Drive grant: true = Drive answered, false = server
     *  rejected (revoked/expired), null = not probed / offline-unknown. */
    val myDriveReachable: Boolean? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val googleAuthManager: AuthManager,
    private val settings: AppSettingsStore,
    private val provider: ICloudStorageProvider,
    private val driveRead: io.github.pnck.gallery.provider.driveread.DriveReadAccess,
    private val workManager: WorkManager,
    private val accountGuard: AccountSwitchGuard,
    private val imageLoader: ImageLoader,
    transportController: TransportController,
) : ViewModel() {

    /**
     * Account isolation (review finding): after any successful identity probe,
     * wipe the previous account's staged data if the email changed. The guard
     * handles Room/cursors/upload-sessions/originals/provider caches; the app
     * layer adds Coil (thumbnails fetched under the old grant, on disk) and the
     * My Drive readonly grant (an old account's token must never ride along).
     *
     * [newSession] marks the just-signed-in path: when the identity probe
     * FAILED there, the session is untrusted and staged data is wiped before
     * any sync runs (the review's residual high). Routine refresh probes never
     * wipe on failure — a flaky network must not nuke a healthy cache.
     */
    private suspend fun enforceAccountIsolation(email: String?, newSession: Boolean) {
        val wiped = withContext(Dispatchers.IO) {
            when {
                email != null -> accountGuard.onAccountObserved(email)
                newSession -> accountGuard.onUntrustedSession()
                else -> false
            }
        }
        if (wiped) {
            imageLoader.memoryCache?.clear()
            withContext(Dispatchers.IO) { imageLoader.diskCache?.clear() }
            driveRead.signOut()
        }
    }

    /** A web link to the cloud backup folder so the user can browse it directly. */
    suspend fun backupFolderLink(): String? =
        withContext(Dispatchers.IO) {
            (provider.backupFolderLink() as? ApiResult.Success)?.data
        }

    private val _state = MutableStateFlow(SettingsState())
    val state = _state.asStateFlow()

    /** The cloud backup folder name (user-configurable, default MyGalleryBackup). */
    val remoteFolderName: StateFlow<String> = settings.remoteFolderName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsStore.DEFAULT_FOLDER_NAME)

    /** Library scope allowlist (empty = all folders) — drives the row summary. */
    val scanBuckets: StateFlow<Set<String>> = settings.scanBuckets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun updateRemoteFolderName(name: String) {
        viewModelScope.launch { settings.setRemoteFolderName(name) }
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
        // Live auth state: a server-rejected grant (401 / invalid_grant) flips this
        // even while the user is sitting on the Settings screen.
        viewModelScope.launch {
            googleAuthManager.authorized.collect { refreshAuthState() }
        }
        // The My Drive (drive.readonly) grant, same treatment — one panel manages both.
        // A stored refresh token is NOT proof of life (server-side revocation is
        // invisible until a call fails), so the panel probes on open.
        viewModelScope.launch {
            driveRead.authorized.collect { myDrive ->
                _state.value = _state.value.copy(myDriveAuthorized = myDrive, myDriveReachable = null)
                if (myDrive) {
                    _state.value = _state.value.copy(myDriveReachable = driveRead.probe())
                }
            }
        }
    }

    /** Revoke the separate drive.readonly grant from the account panel. */
    fun signOutMyDrive() {
        viewModelScope.launch { driveRead.signOut() }
    }

    /** Keystore-backed read is synchronous + can jank the main thread — do it off-main. */
    fun refreshAuthState() {
        viewModelScope.launch {
            val authorized = withContext(Dispatchers.IO) { googleAuthManager.isAuthorized() }
            if (authorized) {
                // Authority check: probe the cloud — "connected" is only true when
                // Drive actually answers (the email doubles as the account-mismatch
                // display). A dead tunnel flips this to reachable=false, honestly.
                val probe = withContext(Dispatchers.IO) { provider.getAccountEmail() }
                when (probe) {
                    is ApiResult.Success -> {
                        enforceAccountIsolation(probe.data, newSession = false)
                        _state.value = _state.value.copy(
                            googleAuthorized = true,
                            cloudReachable = true,
                            accountEmail = probe.data,
                        )
                    }
                    is ApiResult.Error -> _state.value = _state.value.copy(
                        googleAuthorized = true,
                        cloudReachable = false,
                        accountEmail = null,
                    )
                }
            } else {
                _state.value = _state.value.copy(
                    googleAuthorized = false,
                    cloudReachable = null,
                    accountEmail = null,
                )
            }
            // Same honesty for the My Drive grant: probe on every refresh so an
            // expired/revoked grant never presents as healthy.
            if (_state.value.myDriveAuthorized) {
                _state.value = _state.value.copy(myDriveReachable = driveRead.probe())
            }
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
                        is ApiResult.Success -> {
                            // Identity first: if this approval belongs to a
                            // DIFFERENT account, the old account's staged data
                            // must be gone before any new traffic runs — and if
                            // identity can't be confirmed at all, the session is
                            // treated as untrusted and wiped anyway.
                            val email = withContext(Dispatchers.IO) {
                                (provider.getAccountEmail() as? ApiResult.Success)?.data
                            }
                            enforceAccountIsolation(email, newSession = true)
                            _state.value = SettingsState(googleAuthorized = true, signIn = SignInPhase.Idle, accountEmail = email)
                            // First login: kick the chain NOW (downstream → reconcile)
                            // so badges converge — waiting 30 min for the periodic
                            // run leaves the wall stuck at unclassified.
                            SyncPipeline.enqueue(workManager, force = true)
                        }
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
            withContext(Dispatchers.IO) {
                googleAuthManager.signOut()
                // The session's cloud projection leaves with it (owner: signed-out
                // must equal "local wall only" — no cloud-only thumbnails, no
                // backed-up badges). Queue intent / cursors / anchor survive.
                accountGuard.onSignedOut()
                imageLoader.diskCache?.clear() // account-fetched thumbnails off the disk
            }
            imageLoader.memoryCache?.clear()
            _state.value = SettingsState(googleAuthorized = false)
        }
    }
}
