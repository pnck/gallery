package io.github.pnck.gallery.provider.upload

import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.api.DriveApiService
import io.github.pnck.gallery.provider.dto.DriveFileDTO
import io.github.pnck.gallery.provider.dto.DriveUploadMetadata
import java.io.IOException
import kotlinx.coroutines.delay
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * True resumable upload against the Drive v3 protocol (PRD §4.4) — not a
 * single-shot PUT with "resumable" in the name.
 *
 * The protocol state machine:
 *  1. POST init → session URI (persisted in [UploadSessionStore] by photo id).
 *  2. PUT chunks of [CHUNK_SIZE] (a 256 KiB multiple) with explicit Content-Range.
 *     Drive acks intermediate chunks with 308 + `Range: bytes=0-N`; every ack is
 *     committed to the store, so progress survives process death.
 *  3. On ANY failure the session is queried (`Content-Range: bytes &#42;/&#42;`, empty
 *     body): 308+Range → resume at the server-confirmed offset; 200/201 → the
 *     upload had actually completed and only the response was lost (returns the
 *     file — NO duplicate object is created); 404/410 → the session expired
 *     (they live ~a week), re-init once.
 *  4. The final chunk's 200/201 carries the file resource incl. md5Checksum —
 *     verified against the locally computed hash when one is provided.
 */
class ResumableUploader(
    private val api: DriveApiService,
) {

    /** Outcome of a session-status query. */
    private sealed interface SessionStatus {
        /** Server confirms bytes [0, offset) — resume there. */
        data class ResumeAt(val offset: Long) : SessionStatus

        /** The upload had already completed; [file] is the final resource. */
        data class Completed(val file: DriveFileDTO) : SessionStatus

        /** 404/410 — session is gone, must re-init. */
        data object Expired : SessionStatus

        /** Network/server error while querying — retryable. */
        data class Unreachable(val error: ApiResult.Error) : SessionStatus
    }

    /**
     * @param totalBytes exact file size (required — the protocol needs the total
     *   for every Content-Range; the caller fails fast when it can't be known).
     * @param expectedMd5 when non-null, the final response's md5Checksum must
     *   match or the cloud object is deleted and the upload retried.
     */
    suspend fun upload(
        photoId: String,
        chunkBody: (offset: Long, length: Long) -> RequestBody,
        mimeType: String,
        totalBytes: Long,
        expectedMd5: String?,
        metadata: DriveUploadMetadata,
        sessions: UploadSessionStore,
        onProgress: (Int) -> Unit,
    ): ApiResult<DriveFileDTO> = upload(
        photoId, chunkBody, mimeType, totalBytes, expectedMd5, metadata, sessions, onProgress,
        isReinit = false,
    )

    private suspend fun upload(
        photoId: String,
        chunkBody: (offset: Long, length: Long) -> RequestBody,
        mimeType: String,
        totalBytes: Long,
        expectedMd5: String?,
        metadata: DriveUploadMetadata,
        sessions: UploadSessionStore,
        onProgress: (Int) -> Unit,
        isReinit: Boolean,
    ): ApiResult<DriveFileDTO> {
        require(totalBytes > 0) { "totalBytes must be known and positive" }

        // Resume an existing session when the stored shape still matches the file.
        var offset = 0L
        var sessionUri: String? = sessions.get(photoId)
            ?.takeIf { it.totalBytes == totalBytes }
            ?.let { stored ->
                when (val st = queryStatus(stored.sessionUri, totalBytes)) {
                    is SessionStatus.Completed -> return completeUpload(st.file, expectedMd5, photoId, sessions)
                    is SessionStatus.ResumeAt -> {
                        offset = st.offset
                        sessions.updateProgress(photoId, offset)
                        stored.sessionUri
                    }
                    SessionStatus.Expired -> {
                        sessions.remove(photoId)
                        null
                    }
                    is SessionStatus.Unreachable -> return st.error
                }
            }

        if (sessionUri == null) {
            sessionUri = when (val init = initSession(metadata, mimeType, totalBytes)) {
                is ApiResult.Success -> init.data
                is ApiResult.Error -> return init
            }
            sessions.put(photoId, UploadSessionState(sessionUri, 0L, totalBytes, mimeType))
        }

        // Consecutive-error budget for THIS call; the session persists across
        // calls, so returning a retryable error never loses confirmed progress.
        var chainedErrors = 0
        while (offset < totalBytes) {
            val chunkLen = minOf(CHUNK_SIZE, totalBytes - offset)
            val range = "bytes $offset-${offset + chunkLen - 1}/$totalBytes"
            val body = chunkBody(offset, chunkLen)

            val response = try {
                api.uploadToSession(sessionUri, range, body)
            } catch (e: IOException) {
                // Mid-chunk drop: ask the server how much it actually got.
                if (++chainedErrors > MAX_CHAINED_ERRORS) {
                    return ApiResult.Error(-1, e.message ?: "Network I/O error", retryable = true)
                }
                when (val st = queryStatus(sessionUri, totalBytes)) {
                    is SessionStatus.ResumeAt -> {
                        offset = st.offset
                        sessions.updateProgress(photoId, offset)
                    }
                    is SessionStatus.Completed -> return completeUpload(st.file, expectedMd5, photoId, sessions)
                    SessionStatus.Expired -> {
                        sessions.remove(photoId)
                        return reinit(
                            photoId, chunkBody, mimeType, totalBytes, expectedMd5, metadata, sessions, onProgress, isReinit,
                        )
                    }
                    is SessionStatus.Unreachable -> if (++chainedErrors > MAX_CHAINED_ERRORS) return st.error
                }
                continue
            }

            when {
                // Intermediate chunk accepted — commit progress immediately.
                response.code() == HTTP_RESUME_INCOMPLETE -> {
                    chainedErrors = 0
                    offset = parseConfirmedOffset(response.headers()["Range"]) ?: (offset + chunkLen)
                    sessions.updateProgress(photoId, offset)
                    onProgress(((offset * 100) / totalBytes).toInt().coerceAtMost(99))
                }
                response.isSuccessful && response.body() != null ->
                    return completeUpload(response.body()!!, expectedMd5, photoId, sessions)
                response.code() == 404 || response.code() == 410 -> {
                    sessions.remove(photoId)
                    return reinit(
                        photoId, chunkBody, mimeType, totalBytes, expectedMd5, metadata, sessions, onProgress, isReinit,
                    )
                }
                response.code() == 429 || response.code() in 500..599 -> {
                    if (++chainedErrors > MAX_CHAINED_ERRORS) {
                        return ApiResult.Error(response.code(), response.message(), retryable = true)
                    }
                    // Drive's quota model: Retry-After is authoritative when present
                    // (seconds); only fall back to linear backoff when absent.
                    val retryAfterSec = response.headers()["Retry-After"]?.toLongOrNull()
                    delay((retryAfterSec ?: chainedErrors.toLong()).coerceAtMost(MAX_BACKOFF_SEC) * 1000)
                }
                else ->
                    return ApiResult.Error(
                        code = response.code(),
                        message = response.errorBody()?.string()?.take(500) ?: response.message(),
                        retryable = false,
                    )
            }
        }

        // offset == total but the final 200/201 never arrived (lost response on the
        // last chunk): one status query returns the completed file resource.
        return when (val st = queryStatus(sessionUri, totalBytes)) {
            is SessionStatus.Completed -> completeUpload(st.file, expectedMd5, photoId, sessions)
            is SessionStatus.ResumeAt ->
                ApiResult.Error(-1, "server lost confirmed bytes ($st)", retryable = true)
            SessionStatus.Expired -> {
                sessions.remove(photoId)
                ApiResult.Error(-1, "upload session expired at 100%", retryable = true)
            }
            is SessionStatus.Unreachable -> st.error
        }
    }

    /** The one-shot tail after a 404/410: fresh session, file from byte 0. */
    private suspend fun reinit(
        photoId: String,
        chunkBody: (offset: Long, length: Long) -> RequestBody,
        mimeType: String,
        totalBytes: Long,
        expectedMd5: String?,
        metadata: DriveUploadMetadata,
        sessions: UploadSessionStore,
        onProgress: (Int) -> Unit,
        isReinit: Boolean,
    ): ApiResult<DriveFileDTO> {
        // A re-initialized session that immediately dies again is pathological —
        // hand back to the worker's backoff instead of recursing forever.
        if (isReinit) {
            return ApiResult.Error(410, "re-initialized upload session died immediately", retryable = true)
        }
        return upload(
            photoId, chunkBody, mimeType, totalBytes, expectedMd5, metadata, sessions, onProgress,
            isReinit = true,
        )
    }

    /** Verify integrity, clear the session, surface the file. */
    private suspend fun completeUpload(
        file: DriveFileDTO,
        expectedMd5: String?,
        photoId: String,
        sessions: UploadSessionStore,
    ): ApiResult<DriveFileDTO> {
        if (expectedMd5 != null && file.md5Checksum != null && !file.md5Checksum.equals(expectedMd5, true)) {
            // Corrupt in flight (or the file changed under us): the cloud object is
            // NOT the local photo — delete it and let the retry rebuild from byte 0.
            runCatching { api.deleteFile(file.id) }
            sessions.remove(photoId)
            return ApiResult.Error(-1, "md5 mismatch (local $expectedMd5 != cloud ${file.md5Checksum})", retryable = true)
        }
        sessions.remove(photoId)
        return ApiResult.Success(file)
    }

    private suspend fun initSession(
        metadata: DriveUploadMetadata,
        mimeType: String,
        totalBytes: Long,
    ): ApiResult<String> = try {
        val init = api.initResumableUpload(metadata, mimeType, totalBytes)
        when {
            init.isSuccessful ->
                init.headers()["Location"]?.let { ApiResult.Success(it) }
                    ?: ApiResult.Error(-1, "Resumable init returned no session URI", retryable = true)
            else -> ApiResult.Error(
                code = init.code(),
                message = init.errorBody()?.string()?.take(500) ?: init.message(),
                retryable = init.code() == 429 || init.code() in 500..599,
            )
        }
    } catch (e: IOException) {
        ApiResult.Error(-1, e.message ?: "Network I/O error", retryable = true)
    }

    /** `PUT session, Content-Range bytes &#42;/&#42;` — where is the server, really? */
    private suspend fun queryStatus(sessionUri: String, totalBytes: Long): SessionStatus = try {
        val resp = api.uploadToSession(sessionUri, "bytes */$totalBytes", ByteArray(0).toRequestBody(null, 0, 0))
        when {
            resp.code() == HTTP_RESUME_INCOMPLETE ->
                SessionStatus.ResumeAt(parseConfirmedOffset(resp.headers()["Range"]) ?: 0L)
            resp.isSuccessful && resp.body() != null -> SessionStatus.Completed(resp.body()!!)
            resp.code() == 404 || resp.code() == 410 -> SessionStatus.Expired
            resp.code() == 429 || resp.code() in 500..599 ->
                SessionStatus.Unreachable(ApiResult.Error(resp.code(), resp.message(), retryable = true))
            else -> SessionStatus.Unreachable(
                ApiResult.Error(
                    resp.code(),
                    resp.errorBody()?.string()?.take(500) ?: resp.message(),
                    retryable = false,
                ),
            )
        }
    } catch (e: IOException) {
        SessionStatus.Unreachable(ApiResult.Error(-1, e.message ?: "Network I/O error", retryable = true))
    }

    companion object {
        /** 8 MiB — a 256 KiB multiple (protocol rule for non-final chunks). */
        const val CHUNK_SIZE = 8L * 1024 * 1024
        private const val HTTP_RESUME_INCOMPLETE = 308
        private const val MAX_CHAINED_ERRORS = 5
        private const val MAX_BACKOFF_SEC = 60L

        /** `Range: bytes=0-1048575` → the NEXT offset to send (1048576). */
        internal fun parseConfirmedOffset(range: String?): Long? =
            range?.removePrefix("bytes=0-")?.toLongOrNull()?.plus(1)
    }
}
