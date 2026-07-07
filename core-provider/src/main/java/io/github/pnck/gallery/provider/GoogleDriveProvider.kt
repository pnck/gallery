package io.github.pnck.gallery.provider

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.api.DriveApiService
import io.github.pnck.gallery.provider.dto.DriveUploadMetadata
import io.github.pnck.gallery.provider.mapper.DriveMappers
import io.github.pnck.gallery.provider.upload.ContentUriRequestBody
import java.io.IOException
import java.io.InputStream

/**
 * Google Drive driver (T-102, PRD §4.2).
 *
 * Auth: the shared OkHttpClient injects `Authorization: Bearer` for googleapis
 * hosts via GoogleAuthInterceptor — methods here never touch tokens directly.
 *
 * Upload: resumable session for every size (single-shot PUT). Simpler than the
 * multipart/resumable split and immune to Drive's 5 MB multipart cap; chunked
 * resume across worker wake-ups is a follow-up (PRD §4.4).
 */
class GoogleDriveProvider(
    private val api: DriveApiService,
    private val authManager: AuthManager,
    private val resolver: ContentResolver,
) : ICloudStorageProvider {

    override val providerType: ProviderType = ProviderType.G_DRIVE

    override suspend fun authenticate(context: Context): ApiResult<Unit> =
        authManager.startAuthorization(context)

    override suspend fun listPhotos(pageToken: String?): ApiResult<CloudPage> =
        safeApiCall({ api.listFiles(pageToken = pageToken) }) { body ->
            CloudPage(
                files = body.files.map { DriveMappers.toCloudFile(it) },
                nextPageToken = body.nextPageToken,
            )
        }

    override suspend fun fetchChanges(deltaToken: String?): ApiResult<CloudChangeSet> {
        // First call: only fetch the cursor; changes accumulate from here on (PRD §4.3).
        var pageToken: String = deltaToken
            ?: return safeApiCall({ api.getStartPageToken() }) { body ->
                CloudChangeSet(emptyList(), emptyList(), body.startPageToken)
            }

        val upserted = mutableListOf<CloudFile>()
        val deleted = mutableListOf<String>()
        while (true) {
            val page = when (val res = safeApiCall({ api.listChanges(pageToken) }) { it }) {
                is ApiResult.Success -> res.data
                is ApiResult.Error -> return res
            }
            page.changes.forEach { change ->
                val file = change.file
                if (change.removed || file == null) deleted += change.fileId
                else upserted += DriveMappers.toCloudFile(file)
            }
            page.newStartPageToken?.let {
                return ApiResult.Success(CloudChangeSet(upserted, deleted, it))
            }
            pageToken = page.nextPageToken
                ?: return ApiResult.Error(-1, "Changes page missing both tokens", retryable = true)
        }
    }

    override suspend fun uploadFile(
        uri: Uri,
        mimeType: String,
        onProgress: (Int) -> Unit,
    ): ApiResult<CloudFile> {
        val sessionUri = try {
            val init = api.initResumableUpload(DriveUploadMetadata(name = displayNameOf(uri), mimeType = mimeType))
            if (!init.isSuccessful) {
                return ApiResult.Error(
                    code = init.code(),
                    message = init.errorBody()?.string()?.take(500) ?: init.message(),
                    retryable = init.code() == 429 || init.code() in 500..599,
                )
            }
            init.headers()["Location"]
                ?: return ApiResult.Error(-1, "Resumable init returned no session URI", retryable = true)
        } catch (e: IOException) {
            return ApiResult.Error(-1, e.message ?: "Network I/O error", retryable = true)
        }

        val body = ContentUriRequestBody(resolver, uri, mimeType, onProgress)
        return safeApiCall({ api.uploadToSession(sessionUri, body) }) {
            DriveMappers.toCloudFile(it)
        }
    }

    override suspend fun deleteFile(cloudId: String): ApiResult<Unit> =
        safeApiCall({ api.deleteFile(cloudId) }) { }

    override suspend fun downloadOriginal(cloudId: String): ApiResult<InputStream> =
        safeApiCall({ api.downloadFile(cloudId) }) { it.byteStream() }

    override suspend fun getThumbnailUrl(cloudId: String): ApiResult<String> =
        when (val res = safeApiCall({ api.getFile(cloudId) }) { it.thumbnailLink }) {
            is ApiResult.Success ->
                res.data?.let { ApiResult.Success(it) }
                    ?: ApiResult.Error(404, "No thumbnail for $cloudId", retryable = false)
            is ApiResult.Error -> res
        }

    private fun displayNameOf(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "photo-${System.currentTimeMillis()}.jpg"
}
