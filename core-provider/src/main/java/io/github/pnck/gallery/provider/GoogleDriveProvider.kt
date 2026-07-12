package io.github.pnck.gallery.provider

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.api.DriveApiService
import io.github.pnck.gallery.provider.dto.DriveUploadMetadata
import io.github.pnck.gallery.provider.mapper.DriveMappers
import io.github.pnck.gallery.provider.upload.ContentUriRequestBody
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val resolver: ContentResolver,
    /** Current backup-folder name (user-configurable); read fresh so changes apply. */
    private val folderName: suspend () -> String = { DEFAULT_FOLDER_NAME },
) : ICloudStorageProvider {

    override val providerType: ProviderType = ProviderType.G_DRIVE

    private val folderMutex = Mutex()
    @Volatile
    private var cachedFolderId: String? = null
    @Volatile
    private var cachedFolderName: String? = null

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
        val parents = ensureFolderId()?.let { listOf(it) }
        val sessionUri = try {
            val init = api.initResumableUpload(
                DriveUploadMetadata(name = displayNameOf(uri), mimeType = mimeType, parents = parents),
            )
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

    override suspend fun getFileMetadata(cloudId: String): ApiResult<CloudFile> =
        safeApiCall({ api.getFile(cloudId) }) { DriveMappers.toCloudFile(it) }

    override suspend fun backupFolderLink(): ApiResult<String?> {
        val id = ensureFolderId() ?: return ApiResult.Success(null)
        return ApiResult.Success("https://drive.google.com/drive/folders/$id")
    }

    override suspend fun getAccountEmail(): ApiResult<String?> =
        safeApiCall({ api.about() }) { it.user?.emailAddress }

    override suspend fun getThumbnailUrl(cloudId: String): ApiResult<String> =
        when (val res = safeApiCall({ api.getFile(cloudId) }) { it.thumbnailLink }) {
            is ApiResult.Success ->
                res.data?.let { ApiResult.Success(it) }
                    ?: ApiResult.Error(404, "No thumbnail for $cloudId", retryable = false)
            is ApiResult.Error -> res
        }

    /**
     * Find-or-create the app's own "BYOS Gallery" folder so uploads land in one
     * visible place in the user's Drive instead of scattered in My Drive root.
     * With the drive.file scope the app only ever sees this folder and its files.
     * The id is cached; the Mutex avoids racing two first-time uploads into
     * creating duplicate folders. A failure returns null → upload falls back to root.
     */
    private suspend fun ensureFolderId(): String? {
        val name = folderName()
        // Re-resolve if the user renamed the folder since we cached the id.
        cachedFolderId?.let { if (cachedFolderName == name) return it }
        return folderMutex.withLock {
            cachedFolderId?.let { if (cachedFolderName == name) return it }
            val escaped = name.replace("'", "\\'")
            val query = "name = '$escaped' and mimeType = '$FOLDER_MIME' and trashed = false"
            val existing = safeApiCall({ api.listFiles(query = query, pageToken = null) }) {
                it.files.firstOrNull()?.id
            }
            val found = (existing as? ApiResult.Success)?.data
            val resolved = found ?: run {
                val created = safeApiCall({
                    api.createFile(DriveUploadMetadata(name = name, mimeType = FOLDER_MIME))
                }) { it.id }
                (created as? ApiResult.Success)?.data
            }
            cachedFolderId = resolved
            cachedFolderName = name
            resolved
        }
    }

    private fun displayNameOf(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "photo-${System.currentTimeMillis()}.jpg"

    private companion object {
        const val DEFAULT_FOLDER_NAME = "MyGalleryBackup"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
