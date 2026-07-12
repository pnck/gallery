package io.github.pnck.gallery.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Photo access surface (PRD §6.2). syncState literals follow the stable codes
 * asserted in SyncStateTest: 0=PENDING_UPLOAD, 1=SYNCED, 2=CLOUD_ONLY, 3=PENDING_DELETE.
 */
@Dao
interface PhotoDao {

    @Query("SELECT * FROM photos ORDER BY dateTaken DESC")
    fun getPhotosPaged(): PagingSource<Int, PhotoEntity>

    /** Ordered snapshot for the detail-view pager (PRD §9.1). */
    @Query("SELECT * FROM photos ORDER BY dateTaken DESC")
    fun observeAllPhotos(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<PhotoEntity?>

    @Query("SELECT * FROM photos WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PhotoEntity?

    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<PhotoEntity>

    /** Cloud ids already tracked for a provider — downstream sync inserts only new ones. */
    @Query("SELECT cloudId FROM photos WHERE provider = :provider AND cloudId IS NOT NULL")
    suspend fun getKnownCloudIds(provider: String): List<String>

    /** Local copies of already-synced photos — the "free up space" candidate set (PRD §7.3). */
    @Query("SELECT localUri FROM photos WHERE syncState = 1 AND localUri IS NOT NULL")
    suspend fun getSyncedLocalUris(): List<String>

    @Query("SELECT * FROM photos WHERE syncState = 0")
    suspend fun getPendingUploads(): List<PhotoEntity>

    /** Targeted upload set for multi-select sync — only rows still PENDING_UPLOAD. */
    @Query("SELECT * FROM photos WHERE id IN (:ids) AND syncState = 0")
    suspend fun getPendingByIds(ids: List<String>): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE syncState = 1 AND dateTaken < :beforeTs")
    suspend fun getSyncedOlderThan(beforeTs: Long): List<PhotoEntity>

    @Query("UPDATE photos SET cloudId = :cloudId, provider = :provider, syncState = 1 WHERE id = :id")
    suspend fun markAsSynced(id: String, cloudId: String, provider: String)

    @Query("UPDATE photos SET localUri = NULL, syncState = 2 WHERE id IN (:ids)")
    suspend fun markAsCloudOnly(ids: List<String>)

    /** "Save to device" re-materialised a local copy: CLOUD_ONLY → SYNCED (PRD §3.7). */
    @Query("UPDATE photos SET localUri = :localUri, syncState = 1 WHERE id = :id")
    suspend fun markAsSyncedWithLocal(id: String, localUri: String)

    @Upsert
    suspend fun upsertAll(items: List<PhotoEntity>)

    @Query("DELETE FROM photos WHERE cloudId IN (:cloudIds)")
    suspend fun deleteByCloudIds(cloudIds: List<String>)

    @Query("DELETE FROM photos WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM photos WHERE localUri = :localUri LIMIT 1")
    suspend fun findByLocalUri(localUri: String): PhotoEntity?
}

@Dao
interface SyncKeyDao {

    @Query("SELECT * FROM sync_keys WHERE target = :target")
    suspend fun get(target: String): SyncKeyEntity?

    @Upsert
    suspend fun upsert(key: SyncKeyEntity)
}
