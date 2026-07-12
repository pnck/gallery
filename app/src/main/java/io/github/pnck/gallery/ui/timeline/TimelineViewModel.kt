package io.github.pnck.gallery.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.ui.util.DeleteRequest
import io.github.pnck.gallery.work.SyncPipeline
import io.github.pnck.gallery.work.UploadWorker
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** MVI intents of the timeline (PRD §9.2). */
sealed class TimelineIntent {
    data object ForceSync : TimelineIntent()
    data class OnPhotoClick(val photoId: String) : TimelineIntent()
    data object RequestFreeSpace : TimelineIntent()
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Scanning : SyncStatus
    data class Uploading(val done: Int, val total: Int) : SyncStatus
}

/** One-shot feedback for the timeline (rendered as a snackbar). */
sealed interface TimelineEvent {
    data class SyncQueued(val count: Int) : TimelineEvent
    data class SaveStarted(val count: Int) : TimelineEvent
    data class Deleted(val count: Int) : TimelineEvent
}

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val repo: PhotoRepository,
    private val workManager: WorkManager,
) : ViewModel() {

    /** PagingData is collected in the UI, never stored in a state object (PRD §9.2). */
    val photosFlow = repo.getPagedTimelinePhotos().cachedIn(viewModelScope)

    /** Sync indicator for the TopAppBar (PRD §9.1), derived from the unique chain. */
    val syncStatus = workManager
        .getWorkInfosForUniqueWorkFlow(SyncPipeline.UNIQUE_NAME)
        .map { infos -> infos.toSyncStatus() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus.Idle)

    // ── Multi-select (PRD §9.1) ────────────────────────────────────────────
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val events = Channel<TimelineEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    fun toggleSelection(photoId: String) {
        _selection.value = _selection.value.toMutableSet().apply {
            if (!add(photoId)) remove(photoId)
        }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /** Enqueue a targeted upload for the selected (PENDING_UPLOAD) photos. */
    fun syncSelected() {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        SyncPipeline.enqueueTargeted(workManager, ids)
        clearSelection()
        viewModelScope.launch { events.send(TimelineEvent.SyncQueued(ids.size)) }
    }

    /** Re-materialise the selected cloud photos into the shared gallery (PRD §3.7). */
    fun saveSelected() {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            events.send(TimelineEvent.SaveStarted(ids.size))
            ids.forEach { repo.saveToDevice(it) }
        }
    }

    // ── Delete (PENDING_DELETE, PRD §3.7) ──────────────────────────────────
    private val _deleteRequest = MutableStateFlow<DeleteRequest?>(null)
    val deleteRequest: StateFlow<DeleteRequest?> = _deleteRequest.asStateFlow()

    fun deleteSelected() {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            _deleteRequest.value = DeleteRequest(repo.localUrisToDelete(ids), ids)
        }
    }

    fun onDeleteHandled() {
        _deleteRequest.value = null
    }

    /** Called once the system dialog confirmed (or immediately for cloud-only). */
    fun purge(ids: List<String>) {
        viewModelScope.launch {
            repo.purge(ids)
            events.send(TimelineEvent.Deleted(ids.size))
        }
    }

    fun processIntent(intent: TimelineIntent) {
        when (intent) {
            is TimelineIntent.ForceSync -> SyncPipeline.enqueue(workManager)
            is TimelineIntent.RequestFreeSpace -> {
                // T-302: compute deletable URIs, hand to UI for MediaStore.createDeleteRequest.
            }
            is TimelineIntent.OnPhotoClick -> Unit // navigation handled by the screen
        }
    }

    private fun List<WorkInfo>.toSyncStatus(): SyncStatus {
        val running = filter { it.state == WorkInfo.State.RUNNING }
        if (running.isEmpty()) return SyncStatus.Idle
        running.forEach { info ->
            val total = info.progress.getInt(UploadWorker.KEY_PROGRESS_TOTAL, 0)
            if (total > 0) {
                return SyncStatus.Uploading(
                    done = info.progress.getInt(UploadWorker.KEY_PROGRESS_DONE, 0),
                    total = total,
                )
            }
        }
        return SyncStatus.Scanning
    }
}
