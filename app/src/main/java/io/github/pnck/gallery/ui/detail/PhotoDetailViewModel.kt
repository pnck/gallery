package io.github.pnck.gallery.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.domain.TimelinePhoto
import io.github.pnck.gallery.ui.timeline.TimelineQueryHolder
import io.github.pnck.gallery.ui.util.DeleteConfirm
import io.github.pnck.gallery.ui.util.DeleteRequest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Materialisation state of a CLOUD_ONLY original (PRD §9.1, cacheDir only). */
sealed interface OriginalState {
    data object Loading : OriginalState
    data class Ready(val uri: String) : OriginalState
    data object Failed : OriginalState
}

/** One-shot feedback for the detail viewer. */
sealed interface DetailEvent {
    data object Saved : DetailEvent
    data object SaveFailed : DetailEvent
    data object DownloadFailed : DetailEvent
    data object NoEditor : DetailEvent
    data object Deleted : DetailEvent
}

/**
 * Backs the full-screen viewer (T-403, PRD §9.1): a positional snapshot of the
 * timeline for the pager, lazy download of CLOUD_ONLY originals into cacheDir,
 * and the save-to-device action.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PhotoDetailViewModel @Inject constructor(
    private val repo: PhotoRepository,
    @ApplicationContext private val context: Context,
    private val settings: AppSettingsStore,
    queryHolder: TimelineQueryHolder,
) : ViewModel() {

    /** The SAF tree grant (API-30 silent-delete path); null when not granted. */
    val storageTreeUri: StateFlow<String?> = settings.storageTreeUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Persist the uri string; the platform grant itself is taken by the caller. */
    fun saveStorageTree(uri: String) {
        viewModelScope.launch { settings.setStorageTreeUri(uri) }
    }

    // Same shared query as the grid, so the pager swipes through the identical
    // ordered/filtered set the user was just looking at.
    val photos: StateFlow<List<TimelinePhoto>> = queryHolder.query
        .flatMapLatest { repo.getTimeline(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Photo details / EXIF panel (T-403) ─────────────────────────────────
    private val _info = MutableStateFlow<PhotoInfo?>(null)
    val info: StateFlow<PhotoInfo?> = _info.asStateFlow()

    fun showInfo(photo: TimelinePhoto) {
        viewModelScope.launch {
            val details = repo.photoDetails(photo.id) ?: return@launch
            _info.value = withContext(Dispatchers.IO) { ExifReader.build(context, details) }
        }
    }

    fun dismissInfo() {
        _info.value = null
    }

    private val _originals = MutableStateFlow<Map<String, OriginalState>>(emptyMap())
    val originals: StateFlow<Map<String, OriginalState>> = _originals.asStateFlow()

    private val events = Channel<DetailEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    /** Download the cloud original into cacheDir for viewing (idempotent per id). */
    fun ensureOriginal(photo: TimelinePhoto) {
        if (photo.localUri != null || photo.cloudId == null) return
        val current = _originals.value[photo.id]
        if (current is OriginalState.Loading || current is OriginalState.Ready) return

        _originals.value = _originals.value + (photo.id to OriginalState.Loading)
        viewModelScope.launch {
            val uri = repo.cacheOriginal(photo.id)
            if (uri != null) {
                _originals.value = _originals.value + (photo.id to OriginalState.Ready(uri))
            } else {
                _originals.value = _originals.value + (photo.id to OriginalState.Failed)
                events.send(DetailEvent.DownloadFailed)
            }
        }
    }

    /** Save-to-device: cloud original → Pictures/ + SYNCED (PRD §3.7). */
    fun save(photo: TimelinePhoto) {
        viewModelScope.launch {
            val uri = repo.saveToDevice(photo.id)
            events.send(if (uri != null) DetailEvent.Saved else DetailEvent.SaveFailed)
        }
    }

    /**
     * A shareable/editable content uri for this photo: the local copy if present,
     * otherwise the cacheDir original via FileProvider. Null if it can't be produced.
     */
    suspend fun shareableUri(photo: TimelinePhoto): String? =
        photo.localUri ?: repo.cacheOriginal(photo.id)

    fun reportNoEditor() {
        viewModelScope.launch { events.send(DetailEvent.NoEditor) }
    }

    // ── Delete (PENDING_DELETE, PRD §3.7) — confirm, then system dialog ─────
    private val _deleteConfirm = MutableStateFlow<DeleteConfirm?>(null)
    val deleteConfirm: StateFlow<DeleteConfirm?> = _deleteConfirm.asStateFlow()

    private val _deleteRequest = MutableStateFlow<DeleteRequest?>(null)
    val deleteRequest: StateFlow<DeleteRequest?> = _deleteRequest.asStateFlow()

    fun requestDelete(photo: TimelinePhoto) {
        viewModelScope.launch {
            val ids = listOf(photo.id)
            _deleteConfirm.value = DeleteConfirm(ids, repo.countWithoutCloud(ids))
        }
    }

    fun cancelDelete() {
        _deleteConfirm.value = null
    }

    fun confirmDelete() {
        val ids = _deleteConfirm.value?.ids ?: return
        _deleteConfirm.value = null
        viewModelScope.launch {
            _deleteRequest.value = DeleteRequest(repo.localUrisToDelete(ids), ids)
        }
    }

    fun onDeleteHandled() {
        _deleteRequest.value = null
    }

    fun purge(ids: List<String>) {
        viewModelScope.launch {
            repo.purge(ids)
            events.send(DetailEvent.Deleted)
        }
    }
}
