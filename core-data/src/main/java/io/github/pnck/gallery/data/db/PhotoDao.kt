package io.github.pnck.gallery.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * Photo access surface (PRD §6.2). syncState literals follow the stable codes
 * asserted in SyncStateTest: 0=PENDING_UPLOAD, 1=SYNCED, 2=CLOUD_ONLY, 3=PENDING_DELETE.
 */
@Dao
interface PhotoDao {

    /**
     * Timeline page source with a caller-built ORDER BY / WHERE (sort + sync-state +
     * folder allowlist). The query string is assembled in the repository from a
     * closed set of columns/keywords — never user text — so there is no injection
     * surface (PRD §9.1).
     */
    @RawQuery(observedEntities = [PhotoEntity::class])
    fun getPhotosPaged(query: SupportSQLiteQuery): PagingSource<Int, PhotoEntity>

    /**
     * Ordered/filtered snapshot for the detail-view pager (PRD §9.1) — same caller-built
     * query as [getPhotosPaged] so the pager order matches the grid.
     */
    @RawQuery(observedEntities = [PhotoEntity::class])
    fun observePhotos(query: SupportSQLiteQuery): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<PhotoEntity?>

    @Query("SELECT * FROM photos WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PhotoEntity?

    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<PhotoEntity>

    /** Every row — the reconcile-from-truth pass diffs the whole table against cloud + local. */
    @Query("SELECT * FROM photos")
    suspend fun getAllRows(): List<PhotoEntity>

    /** Cloud ids already tracked for a provider — downstream sync inserts only new ones. */
    @Query("SELECT cloudId FROM photos WHERE provider = :provider AND cloudId IS NOT NULL")
    suspend fun getKnownCloudIds(provider: String): List<String>

    /** Synced rows that still hold a local copy — verified individually before freeing. */
    @Query("SELECT * FROM photos WHERE syncState = 1 AND localUri IS NOT NULL")
    suspend fun getSyncedWithLocal(): List<PhotoEntity>

    /** Freeable subset limited to a selection (T-302, per-selection free-up-space). */
    @Query("SELECT * FROM photos WHERE id IN (:ids) AND syncState = 1 AND localUri IS NOT NULL")
    suspend fun getSyncedWithLocalByIds(ids: List<String>): List<PhotoEntity>

    /** Content-hash lookup — the anti-state-machine-failure identity of a photo (PRD §3.5). */
    @Query("SELECT * FROM photos WHERE provider = :provider AND contentHashType = :type AND contentHashValue = :value LIMIT 1")
    suspend fun findByContentHash(provider: String, type: String, value: String): PhotoEntity?

    /** Thumbnail fetcher: read the persisted URL for a cloud photo (avoids a
     *  metadata round-trip per grid cell, PRD §8.3). */
    @Query("SELECT * FROM photos WHERE cloudId = :cloudId LIMIT 1")
    suspend fun findByCloudId(cloudId: String): PhotoEntity?

    /** Persist a refreshed thumbnail URL after the stored one expired. */
    @Query("UPDATE photos SET cloudThumbnailUrl = :url WHERE cloudId = :cloudId")
    suspend fun updateCloudThumbnailUrl(cloudId: String, url: String)

    /**
     * Pending uploads, small files first (quick wins; a huge/poisoned file never
     * blocks the queue) and capped at [MAX_UPLOAD_ATTEMPTS] per file — a file past
     * the cap is revived by reconcile or an explicit user sync, both of which
     * reset the counter.
     */
    @Query(
        "SELECT * FROM photos WHERE syncState = 0 AND excluded = 0 AND uploadAttempts < 8 " +
            "ORDER BY sizeBytes ASC, dateTaken ASC",
    )
    suspend fun getPendingUploads(): List<PhotoEntity>

    /**
     * Atomically claim a row for upload: bumps the attempt counter ONLY if the row
     * is still PENDING_UPLOAD. 0 rows changed = another worker/state transition
     * owns it — the caller must skip (prevents the targeted-vs-bulk double upload).
     */
    @Query("UPDATE photos SET uploadAttempts = uploadAttempts + 1, lastUploadAttemptAt = :now WHERE id = :id AND syncState = 0")
    suspend fun claimUpload(id: String, now: Long): Int

    /** Fresh chances: reconcile re-derived the truth, or the user explicitly syncs. */
    @Query("UPDATE photos SET uploadAttempts = 0 WHERE id IN (:ids)")
    suspend fun resetUploadAttempts(ids: List<String>)

    /** "Clear queue": drop every waiting photo out of automatic backup (kept visible). */
    @Query("UPDATE photos SET excluded = 1 WHERE syncState = 0")
    suspend fun excludeAllPending()

    /** Put photos back in the queue — e.g. the user explicitly selects them to sync. */
    @Query("UPDATE photos SET excluded = 0 WHERE id IN (:ids)")
    suspend fun includeForBackup(ids: List<String>)

    /** Targeted upload set for multi-select sync — only rows still PENDING_UPLOAD. */
    @Query("SELECT * FROM photos WHERE id IN (:ids) AND syncState = 0")
    suspend fun getPendingByIds(ids: List<String>): List<PhotoEntity>

    @Query("UPDATE photos SET cloudId = :cloudId, provider = :provider, syncState = 1, uploadAttempts = 0 WHERE id = :id")
    suspend fun markAsSynced(id: String, cloudId: String, provider: String)

    @Query("UPDATE photos SET localUri = NULL, syncState = 2 WHERE id IN (:ids)")
    suspend fun markAsCloudOnly(ids: List<String>)

    /** "Save to device" re-materialised a local copy: CLOUD_ONLY → SYNCED (PRD §3.7). */
    @Query("UPDATE photos SET localUri = :localUri, syncState = 1 WHERE id = :id")
    suspend fun markAsSyncedWithLocal(id: String, localUri: String)

    /** Persist a verified/known content hash (identity for cross-check, PRD §3.5). */
    @Query("UPDATE photos SET contentHashType = :type, contentHashValue = :value WHERE id = :id")
    suspend fun setContentHash(id: String, type: String, value: String)

    /** Re-link a row to its cloud object when identity matched by hash (state repair). */
    @Query("UPDATE photos SET cloudId = :cloudId, provider = :provider, syncState = :state WHERE id = :id")
    suspend fun linkCloud(id: String, cloudId: String, provider: String, state: Int)

    @Upsert
    suspend fun upsertAll(items: List<PhotoEntity>)

    @Query("DELETE FROM photos WHERE cloudId IN (:cloudIds)")
    suspend fun deleteByCloudIds(cloudIds: List<String>)

    /**
     * Server-side deletion handling (PRD §4.3): a row that still has a LOCAL copy
     * must NOT be dropped — deleting it loses the backup link (and the excluded
     * flag), so the photo vanishes from the timeline and re-imports as a phantom
     * PENDING_UPLOAD on the next scan. Downgrade it instead: the badge flips to
     * "not backed up" and the next upload re-links it. This is also what makes a
     * cloud file that became invisible to the drive.file scope (Changes API reports
     * it as removed) degrade gracefully instead of wiping the row.
     */
    @Query(
        "UPDATE photos SET syncState = 0, cloudId = NULL, provider = NULL, cloudThumbnailUrl = NULL " +
            "WHERE cloudId IN (:cloudIds) AND localUri IS NOT NULL",
    )
    suspend fun downgradeToPendingByCloudIds(cloudIds: List<String>)

    /** Server-side deletion, cloud-only half: rows with no local copy are simply gone. */
    @Query("DELETE FROM photos WHERE cloudId IN (:cloudIds) AND localUri IS NULL")
    suspend fun deleteCloudOnlyByCloudIds(cloudIds: List<String>)

    @Query("DELETE FROM photos WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM photos WHERE localUri = :localUri LIMIT 1")
    suspend fun findByLocalUri(localUri: String): PhotoEntity?

    /** Backfill size/folder/mtime for an existing local row (e.g. after a migration
     *  that defaults these until the next scan sees the row). */
    @Query(
        "UPDATE photos SET sizeBytes = :sizeBytes, bucketId = :bucketId, bucketName = :bucketName, " +
            "dateModifiedSec = :dateModifiedSec WHERE localUri = :localUri",
    )
    suspend fun updateLocalMeta(
        localUri: String,
        sizeBytes: Long,
        bucketId: String?,
        bucketName: String?,
        dateModifiedSec: Long,
    )

    /** Live per-state totals for the sync-status panel (PRD §9.1). */
    @Query(
        "SELECT " +
            "IFNULL(SUM(CASE WHEN syncState = 0 AND excluded = 0 THEN 1 ELSE 0 END), 0) AS pendingUpload, " +
            "IFNULL(SUM(CASE WHEN syncState = 1 THEN 1 ELSE 0 END), 0) AS synced, " +
            "IFNULL(SUM(CASE WHEN syncState = 2 THEN 1 ELSE 0 END), 0) AS cloudOnly, " +
            "IFNULL(SUM(CASE WHEN syncState = 3 THEN 1 ELSE 0 END), 0) AS pendingDelete " +
            "FROM photos",
    )
    fun observeCounts(): Flow<SyncCountsEntity>

    /** How many of these photos have NO cloud copy — deleting them is unrecoverable. */
    @Query("SELECT COUNT(*) FROM photos WHERE id IN (:ids) AND cloudId IS NULL")
    suspend fun countWithoutCloud(ids: List<String>): Int

    /** Aggregate on-device footprint for the space-management screen (T-302). */
    @Query(
        "SELECT " +
            "IFNULL(SUM(CASE WHEN localUri IS NOT NULL THEN sizeBytes ELSE 0 END), 0) AS localBytes, " +
            "IFNULL(SUM(CASE WHEN syncState = 1 AND localUri IS NOT NULL THEN sizeBytes ELSE 0 END), 0) AS freeableBytes, " +
            "IFNULL(SUM(CASE WHEN syncState = 0 AND localUri IS NOT NULL THEN sizeBytes ELSE 0 END), 0) AS notBackedUpBytes, " +
            "IFNULL(SUM(CASE WHEN syncState = 1 AND localUri IS NOT NULL THEN 1 ELSE 0 END), 0) AS freeableCount, " +
            "IFNULL(SUM(CASE WHEN localUri IS NOT NULL THEN 1 ELSE 0 END), 0) AS localCount, " +
            "IFNULL(SUM(CASE WHEN syncState = 2 THEN 1 ELSE 0 END), 0) AS cloudOnlyCount " +
            "FROM photos",
    )
    fun observeStorage(): Flow<StorageSummaryEntity>
}

/** Projection for [PhotoDao.observeStorage]. */
data class StorageSummaryEntity(
    val localBytes: Long,
    val freeableBytes: Long,
    val notBackedUpBytes: Long,
    val freeableCount: Int,
    val localCount: Int,
    val cloudOnlyCount: Int,
)

/** Projection for [PhotoDao.observeCounts]. */
data class SyncCountsEntity(
    val pendingUpload: Int,
    val synced: Int,
    val cloudOnly: Int,
    val pendingDelete: Int,
)

@Dao
interface SyncKeyDao {

    @Query("SELECT * FROM sync_keys WHERE target = :target")
    suspend fun get(target: String): SyncKeyEntity?

    @Upsert
    suspend fun upsert(key: SyncKeyEntity)
}
