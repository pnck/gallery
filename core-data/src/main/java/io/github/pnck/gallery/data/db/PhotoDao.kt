package io.github.pnck.gallery.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Photo access surface (PRD §6.2). syncState literals follow the stable codes
 * asserted in SyncStateTest: 0=PENDING_UPLOAD, 1=SYNCED, 2=CLOUD_ONLY, 3=PENDING_DELETE.
 */
@Dao
interface PhotoDao {

    @Query("SELECT * FROM photos ORDER BY dateTaken DESC")
    fun getPhotosPaged(): PagingSource<Int, PhotoEntity>

    @Query("SELECT * FROM photos WHERE syncState = 0")
    suspend fun getPendingUploads(): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE syncState = 1 AND dateTaken < :beforeTs")
    suspend fun getSyncedOlderThan(beforeTs: Long): List<PhotoEntity>

    @Query("UPDATE photos SET cloudId = :cloudId, provider = :provider, syncState = 1 WHERE id = :id")
    suspend fun markAsSynced(id: String, cloudId: String, provider: String)

    @Query("UPDATE photos SET localUri = NULL, syncState = 2 WHERE id IN (:ids)")
    suspend fun markAsCloudOnly(ids: List<String>)

    @Upsert
    suspend fun upsertAll(items: List<PhotoEntity>)

    @Query("DELETE FROM photos WHERE cloudId IN (:cloudIds)")
    suspend fun deleteByCloudIds(cloudIds: List<String>)

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
