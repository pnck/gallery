package io.github.pnck.gallery.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.data.sync.MediaReconciler
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.domain.SyncCounts
import io.github.pnck.gallery.domain.SyncFilter
import io.github.pnck.gallery.domain.TimelinePhoto
import io.github.pnck.gallery.domain.TimelineSort
import io.github.pnck.gallery.provider.AuthManager
import io.github.pnck.gallery.ui.util.DeleteConfirm
import io.github.pnck.gallery.ui.util.DeleteRequest
import io.github.pnck.gallery.work.ReconcileWorker
import io.github.pnck.gallery.work.SyncPipeline
import io.github.pnck.gallery.work.UploadWorker
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext

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

    /** The last chain FAILED (a worker threw): "up to date" would be a lie. */
    data object Failed : SyncStatus
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

    /** "Rebuild sync state" finished — the tallies ARE the diagnosis when the result
     *  surprises (e.g. synced=0 with a full cloud means the cloud files are not
     *  visible to the app's drive.file scope). */
    data class Rebuilt(val synced: Int, val pendingUpload: Int, val cloudOnly: Int, val pruned: Int) : TimelineEvent
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
    googleAuthManager: AuthManager,
) : ViewModel() {

    /**
     * Whether a cloud account is connected — the UNKNOWN badge ("checking backup
     * status") is only meaningful when classification can actually happen; with no
     * account, unclassified rows are simply LOCAL_ONLY.
     */
    val googleAuthorized: StateFlow<Boolean> = googleAuthManager.authorized

    /** False until the first inline scan attempt finished (success or denied) —
     *  the grid shows a spinner instead of a premature "no photos" empty state. */
    private val _scanSettled = MutableStateFlow(false)
    val scanSettled: StateFlow<Boolean> = _scanSettled.asStateFlow()

    // ── View options: sort (persisted) + sync-state filter (shared) + folders ──
    val sort: StateFlow<TimelineSort> = settings.timelineSort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineSort.DATE_DESC)

    /** Shared with the detail pager so both swipe through the same filtered set. */
    val syncFilter: StateFlow<SyncFilter> = queryHolder.filter

    /** The scan-allowlist / directory filter (empty = all folders). */
    val scanBuckets: StateFlow<Set<String>> = settings.scanBuckets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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
        // The wall must not wait for WorkManager: run the local scan INLINE on
        // entry (a MediaStore metadata query is sub-second; the worker chain's
        // scheduling latency is what left the grid empty on launch).
        viewModelScope.launch { scanInline() }
        // One-time upgrade fix: rows created before the v3 size/bucket columns have
        // sizeBytes=0 (breaks size sorting + the space-management totals). The scan
        // is a full diff now, so a plain chain kick backfills them — no cursor reset.
        viewModelScope.launch {
            if (!settings.sizeBackfilled.first()) {
                SyncPipeline.enqueue(workManager)
                settings.setSizeBackfilled()
            }
        }
        watchRebuildResults()
    }

    /** Local MediaStore → Room scan on the caller's coroutine (idempotent full diff).
     *  Also the permission-grant path: ForceSync runs this FIRST, then kicks the
     *  worker chain for the cloud side. */
    private suspend fun scanInline() {
        withContext(Dispatchers.IO) {
            try {
                reconciler.reconcile()
            } catch (e: SecurityException) {
                // No media permission yet — the screen's permission flow re-triggers.
            }
        }
        _scanSettled.value = true
    }

    fun setSort(newSort: TimelineSort) {
        viewModelScope.launch { settings.setTimelineSort(newSort) }
    }

    fun setSyncFilter(filter: SyncFilter) {
        queryHolder.setFilter(filter)
    }

    /** Sync indicator for the TopAppBar (PRD §9.1), derived from the unique chain. */
    val syncStatus = workManager
        .getWorkInfosForUniqueWorkFlow(SyncPipeline.UNIQUE_NAME)
        .map { infos -> infos.toSyncStatus() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus.Idle)

    /** Live per-state totals for the sync-status panel (PRD §9.1). */
    val syncCounts: StateFlow<SyncCounts> = repo.observeSyncCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncCounts(0, 0, 0, 0, 0))

    /** The autostart experiment's timestamps (0 = none): an outstanding probe
     *  past its delivery window is the only honest "background blocked" signal. */
    val autostartProbeScheduledAt: StateFlow<Long> = settings.autostartProbeScheduledAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val autostartProbeCompletedAt: StateFlow<Long> = settings.autostartProbeCompletedAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Set when the periodic worker found MediaStore empty while local rows
     *  exist — proof that BACKGROUND scans are blind (MIUI foreground-only
     *  app-op), which no permission API can report. 0 = no evidence. */
    val blindScanAt: StateFlow<Long> = settings.blindScanAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Google-Photos-style backup state: running progress, paused, waiting count, or idle.
     *  "Waiting" is the manually built queue ONLY (counts.queued) — freshly scanned
     *  rows are LOCAL_ONLY/UNKNOWN, never auto-waiting (sync is manual by default). */
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
            paused && counts.queued > 0 -> BackupState.Paused(counts.queued)
            counts.queued > 0 -> {
                val failed = all.filter { it.state == WorkInfo.State.SUCCEEDED }
                    .maxOfOrNull { it.outputData.getInt(UploadWorker.KEY_FAILED, 0) } ?: 0
                BackupState.Pending(counts.queued, failed)
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

    /** Explicit "Back up now" / "Sync now": the bulk manual include — queue all
     *  classified pendings, un-pause, and force-restart the chain so a stuck
     *  (retrying) sweep doesn't swallow the request via KEEP. */
    fun backupNow() {
        viewModelScope.launch {
            settings.setBackupPaused(false)
            repo.queueAllPending()
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

    private fun watchRebuildResults() {
        viewModelScope.launch {
            var reportedId: java.util.UUID? = null
            workManager.getWorkInfosForUniqueWorkFlow(SyncPipeline.RECONCILE_NAME).collect { infos ->
                val done = infos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED } ?: return@collect
                if (done.id == reportedId) return@collect
                reportedId = done.id
                val d = done.outputData
                // Only report a real reconcile outcome (a no-account run carries no tallies).
                if (!d.hasKeyWithValueOfType(ReconcileWorker.KEY_SYNCED, Int::class.java)) return@collect
                events.send(
                    TimelineEvent.Rebuilt(
                        synced = d.getInt(ReconcileWorker.KEY_SYNCED, 0),
                        pendingUpload = d.getInt(ReconcileWorker.KEY_PENDING, 0),
                        cloudOnly = d.getInt(ReconcileWorker.KEY_CLOUD_ONLY, 0),
                        pruned = d.getInt(ReconcileWorker.KEY_PRUNED, 0),
                    ),
                )
            }
        }
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
            // Inline scan first (grid fills immediately), THEN the worker chain
            // (downstream → reconcile → upload) for the cloud side.
            is TimelineIntent.ForceSync -> viewModelScope.launch {
                scanInline()
                SyncPipeline.enqueue(workManager)
            }
        }
    }

    private fun List<WorkInfo>.toSyncStatus(): SyncStatus {
        val running = filter { it.state == WorkInfo.State.RUNNING }
        if (running.isEmpty()) {
            // A failed chain means the cloud side was NOT reconciled — never Idle.
            if (any { it.state == WorkInfo.State.FAILED }) return SyncStatus.Failed
            return SyncStatus.Idle
        }
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
