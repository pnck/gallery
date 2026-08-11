package io.github.pnck.gallery.data.sync

import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.data.db.PhotoEntity
import io.github.pnck.gallery.data.scanner.LocalMediaScanner
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.domain.SyncState
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MediaStore → Room reconciliation (T-202, PRD §6.1) — a FULL DIFF, not an
 * incremental import.
 *
 * The owner's model: the wall shows each local photo exactly once, always.
 * The only way a persisted projection can honour that is to be RE-DERIVED
 * wholesale on every scan (derive, don't synchronize): insert what MediaStore
 * has that we don't, refresh metadata, delete local-backed rows whose file is
 * gone. There is no cursor — a cursor is precisely the synchronization state
 * that desynced twice already ("empty wall after upgrade", and id-churn ghosts
 * it can never see). A full MediaStore metadata query is sub-second at this
 * library scale, so the "optimization" never paid for its risk.
 *
 * Two veto rules keep the diff honest:
 *  - BLIND SCAN (returned <5% of the known local-backed rows: permission
 *    revoked, storage unmounted, mid-reindex): apply inserts/updates, skip
 *    deletions — absence of evidence is not evidence of absence;
 *  - SCOPE: when a folder allowlist is set, deletions apply only within it —
 *    out-of-scope rows are hidden by the wall filter, not deleted.
 *
 * One scan at a time process-wide (the timeline entry alone fires up to three
 * triggers); UNIQUE(localUri) + INSERT OR IGNORE make a residual race a no-op.
 * Unknown URIs are inserted as PENDING_UPLOAD with a locally generated UUID
 * primary key (PRD §3.6 — hashes are computed lazily at upload time).
 */
class MediaReconciler(
    private val scanner: LocalMediaScanner,
    private val photoDao: PhotoDao,
    private val settings: AppSettingsStore,
) {
    private val scanMutex = Mutex()

    /** @return number of newly discovered photos. */
    suspend fun reconcile(): Int = scanMutex.withLock {
        val items = scanner.scanIncremental(0L)
        // The scan QUERY succeeded (empty or not): the local library has loaded at
        // least once, so cloud truth may now enter the DB (ReconcileProcessor gate).
        settings.setInitialScanDone()

        // Scan allowlist (PRD §6.1): when non-empty, only import photos from the chosen
        // folders — this is what makes the directory filter "only scan these folders".
        val allowed = settings.scanBuckets.first()
        val inScope = if (allowed.isEmpty()) items else items.filter { it.bucketId in allowed }
        val inScopeUris = inScope.mapTo(HashSet()) { it.contentUri }

        val fresh = mutableListOf<PhotoEntity>()
        for (item in inScope) {
            val existing = photoDao.findByLocalUri(item.contentUri)
            if (existing == null) {
                fresh += PhotoEntity(
                    id = UUID.randomUUID().toString(),
                    localUri = item.contentUri,
                    cloudId = null,
                    provider = null,
                    contentHashType = null,
                    contentHashValue = null,
                    cloudThumbnailUrl = null,
                    dateTaken = item.dateTakenMs,
                    dateModifiedSec = item.dateModifiedSec,
                    width = item.width,
                    height = item.height,
                    sizeBytes = item.sizeBytes,
                    bucketId = item.bucketId,
                    bucketName = item.bucketName,
                    relativePath = item.relativePath,
                    isVideo = item.isVideo,
                    durationMs = item.durationMs,
                    syncState = SyncState.PENDING_UPLOAD,
                )
            } else if (existing.sizeBytes == 0L || existing.bucketId == null) {
                // Backfill metadata for a row that predates the v3 columns (upgraded lib).
                photoDao.updateLocalMeta(
                    item.contentUri, item.sizeBytes, item.bucketId, item.bucketName, item.dateModifiedSec,
                )
            }
        }
        if (fresh.isNotEmpty()) photoDao.insertIgnoreDuplicates(fresh)

        // Diff-delete: local-backed rows whose file is gone from MediaStore.
        // Scope-aware: with an allowlist, only rows INSIDE it participate —
        // a 2%-of-library allowlist is not a blind scan.
        val existing = photoDao.getLocalBackedRows()
        val scopedExisting = existing.filter { allowed.isEmpty() || it.bucketId in allowed || it.bucketId == null }
        val blind = scopedExisting.isNotEmpty() && inScope.size * 20 < scopedExisting.size
        if (!blind) {
            val stale = scopedExisting.filter { row -> row.localUri !in inScopeUris }
            // Chunked: SQLite's host-variable cap (999) bounds each IN(...).
            stale.chunked(500).forEach { chunk -> photoDao.deleteByIds(chunk.map { it.id }) }
        }
        return@withLock fresh.size
    }
}
