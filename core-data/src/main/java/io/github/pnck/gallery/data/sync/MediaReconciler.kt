package io.github.pnck.gallery.data.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.data.db.PhotoEntity
import io.github.pnck.gallery.data.scanner.LocalMediaScanner
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
) {
    private val cursorKey = longPreferencesKey("last_scan_date_modified_sec")

    /** @return number of newly discovered photos. */
    suspend fun reconcile(): Int {
        val since = appContext.scanCursorStore.data.first()[cursorKey] ?: 0L
        val items = scanner.scanIncremental(since)
        if (items.isEmpty()) return 0

        val fresh = items
            .filter { photoDao.findByLocalUri(it.contentUri) == null }
            .map { item ->
                PhotoEntity(
                    id = UUID.randomUUID().toString(),
                    localUri = item.contentUri,
                    cloudId = null,
                    provider = null,
                    contentHashType = null,
                    contentHashValue = null,
                    cloudThumbnailUrl = null,
                    dateTaken = item.dateTakenMs,
                    width = item.width,
                    height = item.height,
                    syncState = SyncState.PENDING_UPLOAD,
                )
            }
        if (fresh.isNotEmpty()) photoDao.upsertAll(fresh)

        val newCursor = items.maxOf { it.dateModifiedSec }
        appContext.scanCursorStore.edit { it[cursorKey] = maxOf(newCursor, since) }
        return fresh.size
    }
}
