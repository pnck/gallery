package io.github.pnck.gallery.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.domain.SyncCounts
import io.github.pnck.gallery.ui.util.DeleteConfirm
import io.github.pnck.gallery.ui.util.DeleteRequest
import io.github.pnck.gallery.work.SyncPipeline
import io.github.pnck.gallery.work.UploadWorker
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** MVI intents of the timeline (PRD §9.2). */
sealed class TimelineIntent {
    data object ForceSync : TimelineIntent()
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

/** A named entry in the sync-queue view (PRD §9.1). */
data class SyncJob(val name: String, val state: WorkInfo.State)

/** Drives the Google-Photos-style backup banner at the top of the timeline. */
sealed interface BackupState {
    /** Nothing pending, nothing running — hide the banner. */
    data object Idle : BackupState

    /** Photos waiting to back up (not currently running); [failed] > 0 offers Retry. */
    data class Pending(val count: Int, val failed: Int) : BackupState

    /** A backup is in flight — show the current item + progress. */
    data class Running(val done: Int, val total: Int, val currentUri: String?, val pct: Int) : BackupState

    /** The bulk backup is paused by the user; [count] still waiting. */
    data class Paused(val count: Int) : BackupState
}

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val repo: PhotoRepository,
    private val settings: AppSettingsStore,
    private val workManager: WorkManager,
) : ViewModel() {

    /** PagingData is collected in the UI, never stored in a state object (PRD §9.2). */
    val photosFlow = repo.getPagedTimelinePhotos().cachedIn(viewModelScope)

    /** Sync indicator for the TopAppBar (PRD §9.1), derived from the unique chain. */
    val syncStatus = workManager
        .getWorkInfosForUniqueWorkFlow(SyncPipeline.UNIQUE_NAME)
        .map { infos -> infos.toSyncStatus() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus.Idle)

    /** Live per-state totals for the sync-status panel (PRD §9.1). */
    val syncCounts: StateFlow<SyncCounts> = repo.observeSyncCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncCounts(0, 0, 0, 0))

    /** Google-Photos-style backup state: running progress, paused, waiting count, or idle. */
    val backupState: StateFlow<BackupState> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(SyncPipeline.UNIQUE_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(SyncPipeline.TARGETED_NAME),
        repo.observeSyncCounts(),
        settings.backupPaused,
    ) { pipeline, targeted, counts, paused ->
        val all = pipeline + targeted
        val running = all.firstOrNull {
            it.state == WorkInfo.State.RUNNING && it.progress.getInt(UploadWorker.KEY_PROGRESS_TOTAL, 0) > 0
        }
        when {
            running != null -> BackupState.Running(
                done = running.progress.getInt(UploadWorker.KEY_PROGRESS_DONE, 0),
                total = running.progress.getInt(UploadWorker.KEY_PROGRESS_TOTAL, 0),
                currentUri = running.progress.getString(UploadWorker.KEY_CURRENT_URI),
                pct = running.progress.getInt(UploadWorker.KEY_CURRENT_PCT, 0),
            )
            paused && counts.pendingUpload > 0 -> BackupState.Paused(counts.pendingUpload)
            counts.pendingUpload > 0 -> {
                val failed = all.filter { it.state == WorkInfo.State.SUCCEEDED }
                    .maxOfOrNull { it.outputData.getInt(UploadWorker.KEY_FAILED, 0) } ?: 0
                BackupState.Pending(counts.pendingUpload, failed)
            }
            else -> BackupState.Idle
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupState.Idle)

    /** Pause the bulk backup and stop any run in progress (explicit syncs still work). */
    fun pauseBackup() {
        viewModelScope.launch {
            settings.setBackupPaused(true)
            workManager.cancelUniqueWork(SyncPipeline.UNIQUE_NAME)
        }
    }

    fun resumeBackup() {
        viewModelScope.launch {
            settings.setBackupPaused(false)
            SyncPipeline.enqueue(workManager)
        }
    }

    /** The sync queue: the state of each unique WorkManager chain. */
    val queue: StateFlow<List<SyncJob>> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(SyncPipeline.UNIQUE_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(SyncPipeline.TARGETED_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(SyncPipeline.PERIODIC_NAME),
    ) { pipeline, targeted, periodic ->
        listOfNotNull(
            pipeline.activeState()?.let { SyncJob("Full sync", it) },
            targeted.activeState()?.let { SyncJob("Selected upload", it) },
            periodic.activeState()?.let { SyncJob("Background sync", it) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Multi-select (PRD §9.1) ────────────────────────────────────────────
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    /** Explicit selection mode so the toolbar can enter it with nothing selected. */
    private val _selectionActive = MutableStateFlow(false)
    val selectionActive: StateFlow<Boolean> = _selectionActive.asStateFlow()

    private val events = Channel<TimelineEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    fun enterSelectionMode() {
        _selectionActive.value = true
    }

    fun exitSelectionMode() {
        _selectionActive.value = false
        _selection.value = emptySet()
    }

    fun toggleSelection(photoId: String) {
        _selectionActive.value = true
        _selection.value = _selection.value.toMutableSet().apply {
            if (!add(photoId)) remove(photoId)
        }
    }

    /** Enqueue a targeted upload for the selected photos (un-excluding any dropped ones). */
    fun syncSelected() {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        exitSelectionMode()
        viewModelScope.launch {
            repo.includeForBackup(ids)
            SyncPipeline.enqueueTargeted(workManager, ids)
            events.send(TimelineEvent.SyncQueued(ids.size))
        }
    }

    /** "Clear queue": stop the current sweep and drop all waiting photos from backup. */
    fun clearBackupQueue() {
        viewModelScope.launch {
            workManager.cancelUniqueWork(SyncPipeline.UNIQUE_NAME)
            repo.clearBackupQueue()
        }
    }

    /** Re-materialise the selected cloud photos into the shared gallery (PRD §3.7). */
    fun saveSelected() {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        exitSelectionMode()
        viewModelScope.launch {
            events.send(TimelineEvent.SaveStarted(ids.size))
            ids.forEach { repo.saveToDevice(it) }
        }
    }

    // ── Delete (PENDING_DELETE, PRD §3.7) — confirm, then system dialog ─────
    private val _deleteConfirm = MutableStateFlow<DeleteConfirm?>(null)
    val deleteConfirm: StateFlow<DeleteConfirm?> = _deleteConfirm.asStateFlow()

    private val _deleteRequest = MutableStateFlow<DeleteRequest?>(null)
    val deleteRequest: StateFlow<DeleteRequest?> = _deleteRequest.asStateFlow()

    /** Step 1: ask for confirmation, surfacing how many photos aren't backed up. */
    fun deleteSelected() {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _deleteConfirm.value = DeleteConfirm(ids, repo.countWithoutCloud(ids))
        }
    }

    fun cancelDelete() {
        _deleteConfirm.value = null
    }

    /** Step 2: the user confirmed — route local files through the system dialog. */
    fun confirmDelete() {
        val ids = _deleteConfirm.value?.ids ?: return
        _deleteConfirm.value = null
        exitSelectionMode()
        viewModelScope.launch {
            _deleteRequest.value = DeleteRequest(repo.localUrisToDelete(ids), ids)
        }
    }

    fun onDeleteHandled() {
        _deleteRequest.value = null
    }

    /** Step 3: the system dialog confirmed (or nothing local) — purge cloud + rows. */
    fun purge(ids: List<String>) {
        viewModelScope.launch {
            repo.purge(ids)
            events.send(TimelineEvent.Deleted(ids.size))
        }
    }

    fun processIntent(intent: TimelineIntent) {
        when (intent) {
            is TimelineIntent.ForceSync -> SyncPipeline.enqueue(workManager)
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

    /** The single most-relevant *active* state of a unique chain, or null if idle. */
    private fun List<WorkInfo>.activeState(): WorkInfo.State? {
        val states = map { it.state }
        return when {
            WorkInfo.State.RUNNING in states -> WorkInfo.State.RUNNING
            WorkInfo.State.ENQUEUED in states -> WorkInfo.State.ENQUEUED
            WorkInfo.State.BLOCKED in states -> WorkInfo.State.BLOCKED
            else -> null
        }
    }
}
