package io.github.pnck.gallery.ui.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.domain.StorageSummary
import io.github.pnck.gallery.work.SyncPipeline
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Device-level storage figures (bytes), read from StatFs — not a Room concern. */
data class DeviceStorage(val totalBytes: Long, val freeBytes: Long)

/** One-shot feedback for the space-management screen. */
sealed interface SpaceEvent {
    data object NothingToFree : SpaceEvent

    /** The sync chain hasn't settled — a fresh sweep was kicked; retry when it lands. */
    data object SyncFirst : SpaceEvent

    data class Freed(val count: Int) : SpaceEvent
}

/**
 * Space-management screen (T-302, PRD §7.3): shows the app's on-device media footprint
 * against real device storage, and is the ONLY place "free up space" lives — releasing
 * verified-backed-up local copies after a double confirmation.
 */
@HiltViewModel
class SpaceManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: PhotoRepository,
    private val workManager: WorkManager,
    private val settings: AppSettingsStore,
) : ViewModel() {

    /** The SAF tree grant (API-30 silent-delete path); null when not granted. */
    val storageTreeUri: StateFlow<String?> = settings.storageTreeUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Persist the uri string; the platform grant itself is taken by the caller. */
    fun saveStorageTree(uri: String) {
        viewModelScope.launch { settings.setStorageTreeUri(uri) }
    }

    val summary: StateFlow<StorageSummary> = repo.observeStorageSummary()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            StorageSummary(0, 0, 0, 0, 0, 0),
        )

    private val _device = MutableStateFlow(DeviceStorage(0, 0))
    val device: StateFlow<DeviceStorage> = _device.asStateFlow()

    /** Verified freeable uris awaiting the system delete dialog (null = nothing pending). */
    private val _freeUris = MutableStateFlow<List<String>?>(null)
    val freeUris: StateFlow<List<String>?> = _freeUris.asStateFlow()

    private val events = Channel<SpaceEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    init {
        refreshDevice()
    }

    fun refreshDevice() {
        viewModelScope.launch {
            _device.value = withContext(Dispatchers.IO) {
                val stat = StatFs(Environment.getDataDirectory().path)
                DeviceStorage(
                    totalBytes = stat.blockCountLong * stat.blockSizeLong,
                    freeBytes = stat.availableBlocksLong * stat.blockSizeLong,
                )
            }
        }
    }

    /**
     * Free-up-space (owner's model): the SYNCED rows in an UP-TO-DATE database
     * are already the verified backed-up set — no spot re-verification, no
     * hash recompute. The only gate is freshness: if the sync chain is running
     * or never completed, kick a sweep and ask for a retry instead of freeing
     * against a stale projection.
     */
    fun requestFreeSpace() {
        viewModelScope.launch {
            if (!withContext(Dispatchers.IO) { isUpToDate() }) {
                SyncPipeline.enqueue(workManager, force = true)
                events.send(SpaceEvent.SyncFirst)
                return@launch
            }
            val uris = repo.freeableLocalUris()
            if (uris.isEmpty()) events.send(SpaceEvent.NothingToFree) else _freeUris.value = uris
        }
    }

    /** Up-to-date = the unique sync chain is idle AND has completed at least once. */
    private fun isUpToDate(): Boolean {
        val infos = workManager.getWorkInfosForUniqueWork(SyncPipeline.UNIQUE_NAME).get()
        if (infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }) return false
        return infos.any { it.state == WorkInfo.State.SUCCEEDED }
    }

    fun onFreeHandled() {
        _freeUris.value = null
    }

    /** After the system delete removed the local files, flip those rows to CLOUD_ONLY. */
    fun confirmFreed(uris: List<String>) {
        viewModelScope.launch {
            repo.releaseLocalCopies(uris)
            events.send(SpaceEvent.Freed(uris.size))
            refreshDevice()
        }
    }
}
