package io.github.pnck.gallery.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Resumable-upload session persistence (PRD §4.4) — see [UploadSessionEntity]. */
@Dao
interface UploadSessionDao {

    @Query("SELECT * FROM upload_sessions WHERE photoId = :photoId LIMIT 1")
    suspend fun get(photoId: String): UploadSessionEntity?

    @Upsert
    suspend fun upsert(session: UploadSessionEntity)

    @Query("UPDATE upload_sessions SET bytesConfirmed = :bytes, updatedAtEpochMs = :now WHERE photoId = :photoId")
    suspend fun updateProgress(photoId: String, bytes: Long, now: Long)

    @Query("DELETE FROM upload_sessions WHERE photoId = :photoId")
    suspend fun delete(photoId: String)

    /** Housekeeping: sessions whose photo row is gone (e.g. pruned by reconcile). */
    @Query("DELETE FROM upload_sessions WHERE photoId NOT IN (SELECT id FROM photos)")
    suspend fun deleteOrphans()
}
