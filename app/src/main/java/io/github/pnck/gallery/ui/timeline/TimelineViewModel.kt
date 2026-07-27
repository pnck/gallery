package io.github.pnck.gallery.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.data.sync.MediaReconciler
import io.github.pnck.gallery.domain.MediaBucket
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.domain.SyncCounts
import io.github.pnck.gallery.domain.SyncFilter
import io.github.pnck.gallery.domain.TimelinePhoto
import io.github.pnck.gallery.domain.TimelineSort
import io.github.pnck.gallery.ui.util.DeleteConfirm
import io.github.pnck.gallery.ui.util.DeleteRequest
import io.github.pnck.gallery.work.SyncPipeline
import io.github.pnck.gallery.work.UploadWorker
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** MVI intents of the timeline (PRD §9.2). */
sealed class TimelineIntent {
    data object ForceSync : TimelineIntent()
}

/** The timeline list PAIRED with the sort that produced it (see TimelineViewModel.timeline). */
data class TimelineList(val sort: TimelineSort, val photos: List<TimelinePhoto>)

private const val TAG = "gallery-timeline"

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
    /** Selected photos weren't backed up yet — queued for backup before space can be freed. */
    data class FreeQueued(val count: Int) : TimelineEvent
    /** Released the local copies of already-backed-up selected photos. */
    data class SpaceFreed(val count: Int) : TimelineEvent
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val repo: PhotoRepository,
    private val settings: AppSettingsStore,
    private val workManager: WorkManager,
    private val reconciler: MediaReconciler,
    private val queryHolder: TimelineQueryHolder,
) : ViewModel() {

    // ── View options: sort (persisted) + sync-state filter (shared) + folders ──
    val sort: StateFlow<TimelineSort> = settings.timelineSort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineSort.DATE_DESC)

    /** Shared with the detail pager so both swipe through the same filtered set. */
    val syncFilter: StateFlow<SyncFilter> = queryHolder.filter

    /** The scan-allowlist / directory filter (empty = all folders). */
    val scanBuckets: StateFlow<Set<String>> = settings.scanBuckets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Device folders offered in the directory picker; loaded on demand. */
    private val _buckets = MutableStateFlow<List<MediaBucket>>(emptyList())
    val buckets: StateFlow<List<MediaBucket>> = _buckets.asStateFlow()

    /**
     * The whole ordered/filtered timeline (metadata only), PAIRED with the sort it
     * was actually ordered by. The grid groups it into date sections whose headers
     * are keyed by year-month label — if the grid ever grouped a stale (old-sort)
     * list with the new sort, the same label would appear in many disjoint runs and
     * LazyVerticalGrid would crash on duplicate keys ("size → date" flash-crash).
     * Deriving (sort, photos) from one flatMapLatest makes the mismatch impossible.
     */
    val timeline: StateFlow<TimelineList> = queryHolder.query
        .flatMapLatest { q ->
            repo.getTimeline(q).map { list -> TimelineList(q.sort, list) }
        }
        .catch { e ->
            Log.w(TAG, "timeline flow failed — showing empty grid instead of crashing", e)
            emit(TimelineList(TimelineSort.DATE_DESC, emptyList()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineList(TimelineSort.DATE_DESC, emptyList()))

    /** Same list as [timeline] (sort-paired source of truth) for consumers that
     *  don't group by date (selection actions, badge logic). */
    val photos: StateFlow<List<TimelinePhoto>> = timeline
        .map { it.photos }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // One-time upgrade fix: rows created before the v3 size/bucket columns have
        // sizeBytes=0 (breaks size sorting + the space-management totals). Force a full
        // rescan once to backfill their metadata, then remember it's done.
        viewModelScope.launch {
            if (!settings.sizeBackfilled.first()) {
                reconciler.resetCursor()
                SyncPipeline.enqueue(workManager)
                settings.setSizeBackfilled()
            }
        }
    }

    fun setSort(newSort: TimelineSort) {
        viewModelScope.launch { settings.setTimelineSort(newSort) }
    }

    fun setSyncFilter(filter: SyncFilter) {
        queryHolder.setFilter(filter)
    }

    /** Load the device folder list (MediaStore buckets) for the directory picker. */
    fun refreshBuckets() {
        viewModelScope.launch { _buckets.value = repo.availableBuckets() }
    }

    /**
     * Change the scan allowlist / directory filter. Widening it must re-import the
     * newly-included folders, so we reset the scan cursor and kick a fresh sweep.
     */
    fun setScanFolders(bucketIds: Set<String>) {
        viewModelScope.launch {
            settings.setScanBuckets(bucketIds)
            reconciler.resetCursor()
            SyncPipeline.enqueue(workManager)
        }
    }

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

    /** Explicit "Back up now" / "Sync now": un-pause and force-restart the chain so a
     *  stuck (retrying) sweep doesn't swallow the request via KEEP. */
    fun backupNow() {
        viewModelScope.launch {
            settings.setBackupPaused(false)
            SyncPipeline.enqueue(workManager, force = true)
        }
    }

    /** The sync queue: the state of each unique WorkManager chain. */
    val queue: StateFlow<List<SyncJob>> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(SyncPipeline.UNIQUE_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(SyncPipeline.TARGETED_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(SyncPipeline.PERIODIC_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(SyncPipeline.RECONCILE_NAME),
    ) { pipeline, targeted, periodic, reconcile ->
        listOfNotNull(
            pipeline.activeState()?.let { SyncJob("Full sync", it) },
            targeted.activeState()?.let { SyncJob("Selected upload", it) },
            periodic.activeState()?.let { SyncJob("Background sync", it) },
            reconcile.activeState()?.let { SyncJob("Rebuild sync state", it) },
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

    /** Select every photo in the current view (Google-Photos parity); toggles off
     *  when everything is already selected. Honors the active sort/filter. */
    fun selectAllVisible() {
        val ids = photos.value.map { it.id }.toSet()
        _selectionActive.value = true
        _selection.value = if (_selection.value == ids) emptySet() else ids
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

    /** "Rebuild sync state": run the reconcile-from-truth pass (full cloud + local diff,
     *  prune drift). Self-heals phantom/stale rows without touching any files. */
    fun rebuildSyncState() {
        SyncPipeline.enqueueReconcile(workManager)
    }

    /** "Clear queue": stop the current sweep and drop all waiting photos from backup. */
    fun clearBackupQueue() {
        viewModelScope.launch {
            workManager.cancelUniqueWork(SyncPipeline.UNIQUE_NAME)
            repo.clearBackupQueue()
        }
    }

    // ── Free space for the selection (PRD §7.3) ────────────────────────────
    // Photos not yet backed up are queued for backup first; already-synced ones
    // have their verified local copy released now (kept as a cloud preview).
    private val _freeRequest = MutableStateFlow<List<String>?>(null)
    val freeRequest: StateFlow<List<String>?> = _freeRequest.asStateFlow()

    fun freeSpaceSelected() {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        exitSelectionMode()
        viewModelScope.launch {
            val notBackedUp = repo.countWithoutCloud(ids)
            if (notBackedUp > 0) {
                // "先同步": put them back in the queue and upload now; they become
                // freeable on a later pass once the backup completes.
                repo.includeForBackup(ids)
                SyncPipeline.enqueueTargeted(workManager, ids)
            }
            val uris = repo.freeableLocalUrisFor(ids)
            if (uris.isNotEmpty()) {
                _freeRequest.value = uris
            } else if (notBackedUp > 0) {
                events.send(TimelineEvent.FreeQueued(notBackedUp))
            }
        }
    }

    fun onFreeHandled() {
        _freeRequest.value = null
    }

    /** After the system delete removed the local files, flip those rows to CLOUD_ONLY. */
    fun confirmFreed(uris: List<String>) {
        viewModelScope.launch {
            repo.releaseLocalCopies(uris)
            events.send(TimelineEvent.SpaceFreed(uris.size))
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
