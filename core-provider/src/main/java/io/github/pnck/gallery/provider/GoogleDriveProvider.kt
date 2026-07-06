package io.github.pnck.gallery.provider

import android.content.Context
import android.net.Uri
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.api.DriveApiService
import java.io.InputStream

/**
 * Google Drive driver (T-102). Skeleton — every method is implemented against
 * [DriveApiService] per the mapping table in PRD §4.2:
 *  - listing: files.list with q=mimeType contains 'image/'
 *  - incremental: Changes API (startPageToken → changes.list), NOT page tokens
 *  - upload: multipart for small files, resumable session for large (PRD §4.4)
 *  - thumbnails: thumbnailLink requires a Bearer header (PRD §8.3)
 */
class GoogleDriveProvider(
    private val api: DriveApiService,
    private val authManager: AuthManager,
) : ICloudStorageProvider {

    override val providerType: ProviderType = ProviderType.G_DRIVE

    override suspend fun authenticate(context: Context): ApiResult<Unit> =
        authManager.startAuthorization(context)

    override suspend fun listPhotos(pageToken: String?): ApiResult<CloudPage> {
        TODO("T-102: files.list + DTO→CloudFile normalization (md5Checksum → ContentHash.Md5)")
    }

    override suspend fun fetchChanges(deltaToken: String?): ApiResult<CloudChangeSet> {
        TODO("T-102: changes.getStartPageToken → changes.list, map removed→deletedCloudIds")
    }

    override suspend fun uploadFile(
        uri: Uri,
        mimeType: String,
        onProgress: (Int) -> Unit,
    ): ApiResult<CloudFile> {
        TODO("T-102: multipart (<4MB) / resumable session (>=4MB), PRD §4.4")
    }

    override suspend fun deleteFile(cloudId: String): ApiResult<Unit> {
        TODO("T-102: DELETE drive/v3/files/{id}")
    }

    override suspend fun downloadOriginal(cloudId: String): ApiResult<InputStream> {
        TODO("T-102: GET drive/v3/files/{id}?alt=media")
    }

    override suspend fun getThumbnailUrl(cloudId: String): ApiResult<String> {
        TODO("T-102: files.get fields=thumbnailLink — short-lived, never persisted")
    }
}
