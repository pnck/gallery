package io.github.pnck.gallery.domain

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

/**
 * Timeline read/action contract consumed by the UI layer (PRD §0, §9.2).
 *
 * Implementations live in :core-data (Room-backed PagingSource + RemoteMediator).
 */
interface PhotoRepository {
    /** Paged timeline ordered by dateTaken DESC. */
    fun getPagedTimelinePhotos(): Flow<PagingData<TimelinePhoto>>

    /**
     * Ordered snapshot of the whole timeline (dateTaken DESC) for the detail-view
     * HorizontalPager, which needs a positional list to swipe between photos.
     */
    fun getTimeline(): Flow<List<TimelinePhoto>>

    /** Observe a single photo (for the detail view's live state/actions). */
    fun observePhoto(id: String): Flow<TimelinePhoto?>

    /** DB-known metadata for the photo-details panel (dims, date, hash, state). */
    suspend fun photoDetails(id: String): PhotoDetails?

    /**
     * Download the cloud original into app cache for viewing only, and return a
     * shareable content:// uri (FileProvider). Never writes DCIM/MediaStore, so
     * no 0/2 duplicate rows appear (PRD §9.1). Returns null when there is no cloud
     * copy or the download fails.
     */
    suspend fun cacheOriginal(id: String): String?

    /**
     * "Save to device": download the cloud original into the shared gallery
     * (Pictures/, scoped storage via MediaStore — never DCIM, invariant #9) and
     * flip the row to SYNCED pointing at the freshly inserted content uri. The
     * reconciler dedups on that uri, so no duplicate PENDING_UPLOAD row is created.
     * Returns the saved content uri, or null on failure.
     */
    suspend fun saveToDevice(id: String): String?

    // ── Delete (PENDING_DELETE, PRD §3.7 / §7.3) ───────────────────────────

    /**
     * Local content uris among [ids] that still have an on-device copy — the UI
     * feeds these to MediaStore.createDeleteRequest (scoped storage, invariant #7).
     */
    suspend fun localUrisToDelete(ids: List<String>): List<String>

    /**
     * Finish a delete: remove the cloud copies (best-effort) and purge the rows.
     * Call after the system delete dialog confirms (or immediately for cloud-only
     * photos, which need no dialog).
     */
    suspend fun purge(ids: List<String>)

    // ── Free up space (T-302, PRD §7.3) ────────────────────────────────────

    /** Local copies of already-synced photos, safe to release. */
    suspend fun freeableLocalUris(): List<String>

    /** After the system delete removed the local files, flip those rows to CLOUD_ONLY. */
    suspend fun releaseLocalCopies(uris: List<String>)
}
