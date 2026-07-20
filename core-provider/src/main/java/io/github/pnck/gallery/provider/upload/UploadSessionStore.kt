package io.github.pnck.gallery.provider.upload

/**
 * A persisted resumable-upload session (PRD §4.4). Survives process death and
 * WorkManager retries: after any failure the uploader asks Drive for the true
 * received offset (`Content-Range: bytes &#42;/&#42;`) and resumes there instead of
 * restarting the file from byte 0.
 */
data class UploadSessionState(
    val sessionUri: String,
    /** Bytes the SERVER has confirmed (the next offset to send). */
    val bytesConfirmed: Long,
    val totalBytes: Long,
    val mimeType: String,
)

/**
 * Where upload sessions live. Implemented by :core-data (Room) so the provider
 * module stays persistence-free; keyed by the local photo id.
 */
interface UploadSessionStore {
    suspend fun get(photoId: String): UploadSessionState?

    suspend fun put(photoId: String, state: UploadSessionState)

    suspend fun updateProgress(photoId: String, bytesConfirmed: Long)

    suspend fun remove(photoId: String)
}
