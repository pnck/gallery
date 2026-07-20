package io.github.pnck.gallery.provider.upload

import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.api.DriveApiService
import io.github.pnck.gallery.provider.dto.DriveAboutResponse
import io.github.pnck.gallery.provider.dto.DriveChangeListResponse
import io.github.pnck.gallery.provider.dto.DriveFileDTO
import io.github.pnck.gallery.provider.dto.DriveFileListResponse
import io.github.pnck.gallery.provider.dto.DriveStartPageTokenResponse
import io.github.pnck.gallery.provider.dto.DriveUploadMetadata
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.Headers.Companion.headersOf
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** In-memory UploadSessionStore (mirrors the Room impl's semantics). */
private class FakeSessionStore : UploadSessionStore {
    val map = mutableMapOf<String, UploadSessionState>()
    override suspend fun get(photoId: String) = map[photoId]
    override suspend fun put(photoId: String, state: UploadSessionState) { map[photoId] = state }
    override suspend fun updateProgress(photoId: String, bytesConfirmed: Long) {
        map[photoId] = map.getValue(photoId).copy(bytesConfirmed = bytesConfirmed)
    }
    override suspend fun remove(photoId: String) { map.remove(photoId) }
}

/** A 308 Resume Incomplete with a Range header (this Retrofit predates the
 *  3-arg Response.error, so build it from a raw okhttp3.Response). */
private fun response308(range: String): Response<DriveFileDTO> {
    val raw = okhttp3.Response.Builder()
        .code(308)
        .message("Resume Incomplete")
        .protocol(Protocol.HTTP_1_1)
        .header("Range", range)
        .request(
            okhttp3.Request.Builder().url("https://upload.example/session/1").build(),
        )
        .build()
    return Response.error("".toResponseBody(null), raw)
}

/**
 * Scripted Drive endpoint: serves resumable uploads from an in-memory [received]
 * buffer, honoring Content-Range exactly like Drive (308 + Range for intermediate
 * chunks, 200 + resource for the final one, 404 for unknown sessions).
 */
private class FakeDriveApi(
    private val fileBytes: ByteArray,
    private val md5: String = "md5-ok",
) : DriveApiService {

    var sessionLive = false
    var received = ByteArray(0)
    var nextSessionUri = "https://upload.example/session/1"
    val chunkRanges = mutableListOf<String>()
    val statusQueries = mutableListOf<String>()
    var deletedFileId: String? = null
    var initCount = 0
    /** Fail the next N chunk PUTs with an IOException (mid-body drop). */
    var ioFailures = 0

    override suspend fun initResumableUpload(
        metadata: DriveUploadMetadata,
        uploadContentType: String?,
        uploadContentLength: Long?,
        fields: String,
    ): Response<Unit> {
        initCount++
        sessionLive = true
        received = ByteArray(0)
        return Response.success(null, headersOf("Location", nextSessionUri))
    }

    override suspend fun uploadToSession(
        sessionUri: String,
        contentRange: String,
        body: RequestBody,
        fields: String,
    ): Response<DriveFileDTO> {
        if (!sessionLive) {
            return Response.error(404, "{}".toResponseBody("application/json".toMediaType()))
        }
        if (contentRange.startsWith("bytes */")) {
            // Status query: 308 + confirmed range, or 200 if the file completed.
            statusQueries += contentRange
            if (received.contentEquals(fileBytes)) {
                return Response.success(
                    DriveFileDTO(id = "cloud-1", name = "photo.jpg", size = fileBytes.size.toLong(), md5Checksum = md5),
                )
            }
            val last = if (received.isEmpty()) -1L else received.size - 1L
            return response308("bytes=0-$last")
        }
        if (ioFailures > 0) {
            ioFailures--
            throw IOException("connection dropped mid-body")
        }
        // Chunk PUT: "bytes first-last/total"
        chunkRanges += contentRange
        val m = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(contentRange)!!
        val (first, last, total) = Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong())
        require(first == received.size.toLong()) { "gap: server at ${received.size}, chunk starts $first" }
        val buf = Buffer()
        body.writeTo(buf)
        val bytes = buf.readByteArray()
        require(bytes.size.toLong() == last - first + 1) { "chunk body size ${bytes.size} != range" }
        received += bytes
        return if (last == total - 1) {
            Response.success(DriveFileDTO(id = "cloud-1", name = "photo.jpg", size = total, md5Checksum = md5))
        } else {
            response308("bytes=0-$last")
        }
    }

    override suspend fun deleteFile(fileId: String): Response<Unit> {
        deletedFileId = fileId
        return Response.success(null)
    }

    // ── unused in these tests ──────────────────────────────────────────────
    override suspend fun listFiles(query: String, pageToken: String?, pageSize: Int, fields: String) =
        throw UnsupportedOperationException()
    override suspend fun about(fields: String): Response<DriveAboutResponse> = throw UnsupportedOperationException()
    override suspend fun getStartPageToken(): Response<DriveStartPageTokenResponse> = throw UnsupportedOperationException()
    override suspend fun listChanges(pageToken: String, fields: String): Response<DriveChangeListResponse> =
        throw UnsupportedOperationException()
    override suspend fun createFile(metadata: DriveUploadMetadata, fields: String) = throw UnsupportedOperationException()
    override suspend fun getFile(fileId: String, fields: String) = throw UnsupportedOperationException()
    override suspend fun browseFolder(q: String, pageToken: String?, orderBy: String, pageSize: Int, fields: String):
        Response<DriveFileListResponse> = throw UnsupportedOperationException()
    override suspend fun downloadFile(fileId: String, range: String?): Response<ResponseBody> =
        throw UnsupportedOperationException()
}

class ResumableUploaderTest {

    private val metadata = DriveUploadMetadata(name = "photo.jpg", mimeType = "image/jpeg")

    private fun uploader(api: DriveApiService) = ResumableUploader(api)

    private fun body(bytes: ByteArray): (Long, Long) -> RequestBody = { offset, length ->
        bytes.copyOfRange(offset.toInt(), (offset + length).toInt()).toRequestBody(null, 0, length.toInt())
    }

    @Test
    fun `small file uploads in a single final chunk`() = runTest {
        val bytes = ByteArray(1000) { it.toByte() }
        val api = FakeDriveApi(bytes)
        val store = FakeSessionStore()

        val res = uploader(api).upload("p1", body(bytes), "image/jpeg", bytes.size.toLong(), null, metadata, store) {}

        assertTrue(res is ApiResult.Success)
        assertEquals(listOf("bytes 0-999/1000"), api.chunkRanges)
        assertNull(store.map["p1"]) // session cleaned up
        assertEquals(1, api.initCount)
    }

    @Test
    fun `large file uploads in 256KiB-multiple chunks with committed progress`() = runTest {
        val total = (ResumableUploader.CHUNK_SIZE * 2 + 123).toInt()
        val bytes = ByteArray(total) { (it % 251).toByte() }
        val api = FakeDriveApi(bytes)
        val store = FakeSessionStore()
        val pcts = mutableListOf<Int>()

        val res = uploader(api).upload("p1", body(bytes), "image/jpeg", total.toLong(), null, metadata, store) {
            pcts += it
        }

        assertTrue(res is ApiResult.Success)
        assertEquals(3, api.chunkRanges.size)
        assertEquals("bytes 0-${ResumableUploader.CHUNK_SIZE - 1}/$total", api.chunkRanges[0])
        assertEquals("bytes ${ResumableUploader.CHUNK_SIZE}-${ResumableUploader.CHUNK_SIZE * 2 - 1}/$total", api.chunkRanges[1])
        assertEquals("bytes ${ResumableUploader.CHUNK_SIZE * 2}-${total - 1}/$total", api.chunkRanges[2])
        assertEquals(listOf(49, 99), pcts) // progress after each acked intermediate chunk
        assertNull(store.map["p1"])
    }

    @Test
    fun `resumes from the server-confirmed offset, not byte zero`() = runTest {
        val total = (ResumableUploader.CHUNK_SIZE + 500).toInt()
        val bytes = ByteArray(total) { (it % 241).toByte() }
        val api = FakeDriveApi(bytes)
        // A previous process died after the first chunk was confirmed.
        api.received = bytes.copyOfRange(0, ResumableUploader.CHUNK_SIZE.toInt())
        api.sessionLive = true
        val store = FakeSessionStore().apply {
            map["p1"] = UploadSessionState("https://upload.example/session/1", ResumableUploader.CHUNK_SIZE, total.toLong(), "image/jpeg")
        }

        val res = uploader(api).upload("p1", body(bytes), "image/jpeg", total.toLong(), null, metadata, store) {}

        assertTrue(res is ApiResult.Success)
        assertEquals(0, api.initCount) // no re-init
        assertEquals(listOf("bytes ${ResumableUploader.CHUNK_SIZE}-${total - 1}/$total"), api.chunkRanges)
        assertTrue(api.statusQueries.isNotEmpty())
    }

    @Test
    fun `lost final response completes from the status query without a duplicate file`() = runTest {
        val bytes = ByteArray(500) { it.toByte() }
        val api = FakeDriveApi(bytes)
        // The upload actually completed server-side; only the 200 was lost.
        api.received = bytes
        api.sessionLive = true
        val store = FakeSessionStore().apply {
            map["p1"] = UploadSessionState("https://upload.example/session/1", 0, bytes.size.toLong(), "image/jpeg")
        }

        val res = uploader(api).upload("p1", body(bytes), "image/jpeg", bytes.size.toLong(), null, metadata, store) {}

        assertTrue(res is ApiResult.Success)
        assertEquals(0, api.initCount)
        assertTrue(api.chunkRanges.isEmpty()) // NOT re-uploaded — no duplicate Drive file
    }

    @Test
    fun `expired session re-inits and restarts`() = runTest {
        val bytes = ByteArray(500) { it.toByte() }
        val api = FakeDriveApi(bytes)
        api.sessionLive = false // stored session is dead on the server
        val store = FakeSessionStore().apply {
            map["p1"] = UploadSessionState("https://upload.example/session/dead", 100, bytes.size.toLong(), "image/jpeg")
        }

        val res = uploader(api).upload("p1", body(bytes), "image/jpeg", bytes.size.toLong(), null, metadata, store) {}

        assertTrue(res is ApiResult.Success)
        assertEquals(1, api.initCount) // re-initialized exactly once
        assertEquals(listOf("bytes 0-499/500"), api.chunkRanges)
    }

    @Test
    fun `mid-chunk IO failure resyncs from the server offset and continues`() = runTest {
        val total = (ResumableUploader.CHUNK_SIZE + 100).toInt()
        val bytes = ByteArray(total) { (it % 231).toByte() }
        val api = FakeDriveApi(bytes)
        val store = FakeSessionStore()
        // The first chunk lands server-side, but the connection drops before the
        // 308 arrives; the retry must resume at CHUNK_SIZE, not re-send from 0.
        val delegate = api
        val flaky = object : DriveApiService by delegate {
            var dropped = false
            override suspend fun uploadToSession(
                sessionUri: String,
                contentRange: String,
                body: RequestBody,
                fields: String,
            ): Response<DriveFileDTO> {
                if (!dropped && contentRange.startsWith("bytes 0-")) {
                    dropped = true
                    delegate.received = bytes.copyOfRange(0, ResumableUploader.CHUNK_SIZE.toInt())
                    throw IOException("response lost")
                }
                return delegate.uploadToSession(sessionUri, contentRange, body, fields)
            }
        }

        val res = uploader(flaky).upload("p1", body(bytes), "image/jpeg", total.toLong(), null, metadata, store) {}

        assertTrue(res is ApiResult.Success)
        // After the drop, the only chunk sent is the resume from CHUNK_SIZE.
        assertEquals(listOf("bytes ${ResumableUploader.CHUNK_SIZE}-${total - 1}/$total"), api.chunkRanges)
    }

    @Test
    fun `md5 mismatch deletes the corrupt cloud object and fails retryable`() = runTest {
        val bytes = ByteArray(500) { it.toByte() }
        val api = FakeDriveApi(bytes)
        val store = FakeSessionStore()

        val res = uploader(api)
            .upload("p1", body(bytes), "image/jpeg", bytes.size.toLong(), "DIFFERENT-md5", metadata, store) {}

        assertTrue(res is ApiResult.Error && res.retryable)
        assertEquals("cloud-1", api.deletedFileId)
        assertNull(store.map["p1"])
    }

    @Test
    fun `re-init that immediately dies stops instead of recursing`() = runTest {
        val bytes = ByteArray(500) { it.toByte() }
        val api = FakeDriveApi(bytes)
        val store = FakeSessionStore().apply {
            map["p1"] = UploadSessionState("https://upload.example/session/dead", 0, bytes.size.toLong(), "image/jpeg")
        }
        // Every session the server sees is dead: status 404, re-init, chunk 404.
        val dying = object : DriveApiService by api {
            override suspend fun uploadToSession(
                sessionUri: String,
                contentRange: String,
                body: RequestBody,
                fields: String,
            ): Response<DriveFileDTO> {
                api.sessionLive = false
                return Response.error(404, "{}".toResponseBody("application/json".toMediaType()))
            }
        }

        val res = uploader(dying).upload("p1", body(bytes), "image/jpeg", bytes.size.toLong(), null, metadata, store) {}

        assertTrue(res is ApiResult.Error && res.retryable) // bounded — no infinite recursion
    }

    @Test
    fun `parseConfirmedOffset reads the Range header`() {
        assertEquals(1_048_576L, ResumableUploader.parseConfirmedOffset("bytes=0-1048575"))
        assertEquals(0L, ResumableUploader.parseConfirmedOffset("bytes=0--1"))
        assertNull(ResumableUploader.parseConfirmedOffset(null))
        assertNull(ResumableUploader.parseConfirmedOffset("garbage"))
    }
}
