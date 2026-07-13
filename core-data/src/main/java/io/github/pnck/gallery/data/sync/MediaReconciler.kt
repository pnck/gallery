package io.github.pnck.gallery.data.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.data.db.PhotoEntity
import io.github.pnck.gallery.data.scanner.LocalMediaScanner
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.domain.SyncState
import java.util.UUID
import kotlinx.coroutines.flow.first

private val Context.scanCursorStore by preferencesDataStore(name = "scan_cursor")

/**
 * MediaStore → Room reconciliation (T-202, PRD §6.1).
 *
 * Incremental: the DATE_MODIFIED cursor persists in DataStore so each run only
 * queries rows newer than the previous scan. Unknown content URIs are inserted
 * as PENDING_UPLOAD with a locally generated UUID primary key (PRD §3.6 — never
 * file hashes, which are computed lazily at upload time).
 */
class MediaReconciler(
    private val appContext: Context,
    private val scanner: LocalMediaScanner,
    private val photoDao: PhotoDao,
    private val settings: AppSettingsStore,
) {
    private val cursorKey = longPreferencesKey("last_scan_date_modified_sec")

    /** @return number of newly discovered photos. */
    suspend fun reconcile(): Int {
        val since = appContext.scanCursorStore.data.first()[cursorKey] ?: 0L
        val items = scanner.scanIncremental(since)
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
        val newCursor = items.maxOf { it.dateModifiedSec }
        appContext.scanCursorStore.edit { it[cursorKey] = maxOf(newCursor, since) }
        return fresh.size
    }

    /** Force the next [reconcile] to re-scan the whole library — used when the scan
     *  allowlist changes so newly-included folders get imported. */
    suspend fun resetCursor() {
        appContext.scanCursorStore.edit { it[cursorKey] = 0L }
    }
}
