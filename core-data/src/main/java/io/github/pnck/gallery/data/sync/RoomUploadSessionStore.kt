package io.github.pnck.gallery.data.sync

import io.github.pnck.gallery.data.db.UploadSessionDao
import io.github.pnck.gallery.data.db.UploadSessionEntity
import io.github.pnck.gallery.provider.upload.UploadSessionState
import io.github.pnck.gallery.provider.upload.UploadSessionStore

/** Room-backed [UploadSessionStore] — resumable sessions survive process death. */
class RoomUploadSessionStore(
    private val dao: UploadSessionDao,
    private val now: () -> Long = System::currentTimeMillis,
) : UploadSessionStore {

    override suspend fun get(photoId: String): UploadSessionState? =
        dao.get(photoId)?.let {
            UploadSessionState(it.sessionUri, it.bytesConfirmed, it.totalBytes, it.mimeType)
        }

    override suspend fun put(photoId: String, state: UploadSessionState) {
        dao.upsert(
            UploadSessionEntity(
                photoId = photoId,
                sessionUri = state.sessionUri,
                bytesConfirmed = state.bytesConfirmed,
                totalBytes = state.totalBytes,
                mimeType = state.mimeType,
                updatedAtEpochMs = now(),
            ),
        )
    }

    override suspend fun updateProgress(photoId: String, bytesConfirmed: Long) {
        dao.updateProgress(photoId, bytesConfirmed, now())
    }

    override suspend fun remove(photoId: String) = dao.delete(photoId)
}
