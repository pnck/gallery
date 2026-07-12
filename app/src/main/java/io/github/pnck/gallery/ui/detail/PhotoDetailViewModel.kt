package io.github.pnck.gallery.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.domain.TimelinePhoto
import io.github.pnck.gallery.ui.util.DeleteRequest
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
@HiltViewModel
class PhotoDetailViewModel @Inject constructor(
    private val repo: PhotoRepository,
) : ViewModel() {

    val photos: StateFlow<List<TimelinePhoto>> = repo.getTimeline()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    // ── Delete (PENDING_DELETE, PRD §3.7) ──────────────────────────────────
    private val _deleteRequest = MutableStateFlow<DeleteRequest?>(null)
    val deleteRequest: StateFlow<DeleteRequest?> = _deleteRequest.asStateFlow()

    fun requestDelete(photo: TimelinePhoto) {
        viewModelScope.launch {
            _deleteRequest.value = DeleteRequest(repo.localUrisToDelete(listOf(photo.id)), listOf(photo.id))
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
