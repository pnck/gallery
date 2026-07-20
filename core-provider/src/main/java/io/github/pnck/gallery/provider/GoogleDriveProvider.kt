package io.github.pnck.gallery.provider

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.api.DriveApiService
import io.github.pnck.gallery.provider.dto.DriveUploadMetadata
import io.github.pnck.gallery.provider.mapper.DriveMappers
import io.github.pnck.gallery.provider.upload.ContentUriRangeRequestBody
import io.github.pnck.gallery.provider.upload.ResumableUploader
import io.github.pnck.gallery.provider.upload.UploadSessionStore
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
 * Upload: true resumable sessions via [ResumableUploader] (PRD §4.4) — chunked
 * PUTs with Content-Range, server-confirmed offsets persisted per photo, and
 * MD5 verification of the final object. The [uploadApi] is a SEPARATE Retrofit
 * instance on the isolated upload client (HTTP/1.1, own pool) so bulk transfers
 * get real parallel TCP connections instead of coalescing onto one HTTP/2
 * stream that interactive requests also depend on.
 */
class GoogleDriveProvider(
    private val api: DriveApiService,
    private val uploadApi: DriveApiService,
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
        photoId: String,
        uri: Uri,
        mimeType: String,
        totalBytes: Long,
        expectedMd5: String?,
        sessions: UploadSessionStore,
        onProgress: (Int) -> Unit,
    ): ApiResult<CloudFile> {
        val parents = ensureFolderId()?.let { listOf(it) }
        val metadata = DriveUploadMetadata(name = displayNameOf(uri), mimeType = mimeType, parents = parents)
        return when (
            val res = ResumableUploader(uploadApi).upload(
                photoId = photoId,
                chunkBody = { offset, length -> ContentUriRangeRequestBody(resolver, uri, offset, length, mimeType) },
                mimeType = mimeType,
                totalBytes = totalBytes,
                expectedMd5 = expectedMd5,
                metadata = metadata,
                sessions = sessions,
                onProgress = onProgress,
            )
        ) {
            is ApiResult.Success -> ApiResult.Success(DriveMappers.toCloudFile(res.data))
            is ApiResult.Error -> res
        }
    }

    override suspend fun deleteFile(cloudId: String): ApiResult<Unit> =
        safeApiCall({ api.deleteFile(cloudId) }) { }

    override suspend fun downloadOriginal(cloudId: String, offset: Long): ApiResult<InputStream> = try {
        val resp = api.downloadFile(cloudId, range = if (offset > 0) "bytes=$offset-" else null)
        when {
            // A resume MUST come back 206 — a 200 is the whole file, and appending
            // it at `offset` would corrupt the partial download.
            resp.isSuccessful && resp.body() != null && (offset == 0L || resp.code() == 206) ->
                ApiResult.Success(resp.body()!!.byteStream())
            resp.isSuccessful -> {
                resp.body()?.close()
                ApiResult.Error(416, "range request not honored (got ${resp.code()})", retryable = false)
            }
            else -> ApiResult.Error(
                code = resp.code(),
                message = resp.errorBody()?.string()?.take(500) ?: resp.message(),
                retryable = resp.code() == 429 || resp.code() in 500..599,
            )
        }
    } catch (e: IOException) {
        ApiResult.Error(-1, e.message ?: "Network I/O error", retryable = true)
    }

    override suspend fun browse(folderId: String, pageToken: String?): ApiResult<DriveListing> =
        safeApiCall({
            api.browseFolder(q = "'$folderId' in parents and trashed = false", pageToken = pageToken)
        }) { resp ->
            DriveListing(
                entries = resp.files.map { dto ->
                    DriveEntry(
                        id = dto.id,
                        name = dto.name ?: dto.id,
                        mimeType = dto.mimeType ?: "application/octet-stream",
                        sizeBytes = dto.size,
                        thumbnailUrl = dto.thumbnailLink,
                    )
                },
                nextPageToken = resp.nextPageToken,
            )
        }

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
