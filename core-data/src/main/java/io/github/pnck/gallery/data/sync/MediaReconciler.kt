package io.github.pnck.gallery.data.sync

import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.data.db.PhotoEntity
import io.github.pnck.gallery.data.db.SyncKeyDao
import io.github.pnck.gallery.data.db.SyncKeyEntity
import io.github.pnck.gallery.data.scanner.LocalMediaScanner
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.domain.SyncState
import java.util.UUID
import kotlinx.coroutines.flow.first

/**
 * MediaStore → Room reconciliation (T-202, PRD §6.1).
 *
 * Incremental: the DATE_MODIFIED cursor persists in `sync_keys` (Room) so each run
 * only queries rows newer than the previous scan. The cursor deliberately lives in
 * the SAME cache it cursors over: the DB is a pure cache recreated by
 * `fallbackToDestructiveMigration` on schema bumps, and a cursor that outlives the
 * library it describes is a silent killer — the scan then only looks for photos
 * newer than the stale cursor, imports nothing, and the wall stays empty forever
 * (the "No photos yet after upgrade" report). Wiped together, they cannot desync.
 *
 * Unknown content URIs are inserted as PENDING_UPLOAD with a locally generated UUID
 * primary key (PRD §3.6 — never file hashes, which are computed lazily at upload
 * time).
 */
class MediaReconciler(
    private val scanner: LocalMediaScanner,
    private val photoDao: PhotoDao,
    private val syncKeyDao: SyncKeyDao,
    private val settings: AppSettingsStore,
) {
    /** @return number of newly discovered photos. */
    suspend fun reconcile(): Int {
        var since = syncKeyDao.get(SCAN_CURSOR_TARGET)?.deltaToken?.toLongOrNull() ?: 0L
        if (since > 0 && photoDao.count() == 0) {
            // A cursor without a library is a contradiction (backup/restore edge,
            // partial data wipe): the cursor derives from the table, so trust the
            // table and rescan from zero instead of trusting the cursor.
            since = 0L
        }
        val items = scanner.scanIncremental(since)
        // The scan QUERY succeeded (empty or not): the local library has loaded at
        // least once, so cloud truth may now enter the DB (ReconcileProcessor gate).
        settings.setInitialScanDone()
        if (items.isEmpty()) return 0

        // Scan allowlist (PRD §6.1): when non-empty, only import photos from the chosen
        // folders — this is what makes the directory filter "only scan these folders".
        val allowed = settings.scanBuckets.first()
        val inScope = if (allowed.isEmpty()) items else items.filter { it.bucketId in allowed }

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
                    syncState = SyncState.PENDING_UPLOAD,
                )
            } else if (existing.sizeBytes == 0L || existing.bucketId == null) {
                // Backfill metadata for a row that predates the v3 columns (upgraded lib).
                photoDao.updateLocalMeta(
                    item.contentUri, item.sizeBytes, item.bucketId, item.bucketName, item.dateModifiedSec,
                )
            }
        }
        if (fresh.isNotEmpty()) photoDao.upsertAll(fresh)

        // Advance the cursor over EVERY scanned row (even ones filtered out), so an
        // unchanged allowlist doesn't re-scan them next time. Widening the allowlist
        // resets the cursor (see resetCursor) to force a one-time full re-import.
        writeCursor(maxOf(items.maxOf { it.dateModifiedSec }, since))
        return fresh.size
    }

    /** Force the next [reconcile] to re-scan the whole library — used when the scan
     *  allowlist changes so newly-included folders get imported. */
    suspend fun resetCursor() {
        writeCursor(0L)
    }

    private suspend fun writeCursor(dateModifiedSec: Long) {
        syncKeyDao.upsert(
            SyncKeyEntity(SCAN_CURSOR_TARGET, nextPageToken = null, deltaToken = dateModifiedSec.toString()),
        )
    }

    private companion object {
        /** sync_keys target for the local-scan DATE_MODIFIED cursor (epoch seconds). */
        const val SCAN_CURSOR_TARGET = "local_scan"
    }
}
