package io.github.pnck.gallery.provider

import android.net.Uri
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.api.GraphApiService
import java.io.InputStream

/**
 * OneDrive (Microsoft Graph) driver (T-103). Skeleton — per PRD §4.2:
 *  - listing: /me/drive/special/photos/children, paging via @odata.nextLink
 *  - incremental: /me/drive/root/delta + deltaLink, deleted facet → removals
 *  - hashes: quickXorHash (NO md5 — PRD §3.5)
 *  - thumbnails: pre-authorized URLs, must NOT add an Authorization header (PRD §8.3)
 */
class OneDriveProvider(
    private val api: GraphApiService,
) : ICloudStorageProvider {

    override val providerType: ProviderType = ProviderType.ONE_DRIVE

    override suspend fun listPhotos(pageToken: String?): ApiResult<CloudPage> {
        TODO("T-103: children + nextLink paging, quickXorHash → ContentHash.QuickXor")
    }

    override suspend fun fetchChanges(deltaToken: String?): ApiResult<CloudChangeSet> {
        TODO("T-103: delta → deltaLink, parse deleted facet into deletedCloudIds")
    }

    override suspend fun uploadFile(
        uri: Uri,
        mimeType: String,
        onProgress: (Int) -> Unit,
    ): ApiResult<CloudFile> {
        TODO("T-103: simple PUT (<4MB) / createUploadSession chunked PUT (PRD §4.4)")
    }

    override suspend fun deleteFile(cloudId: String): ApiResult<Unit> {
        TODO("T-103: DELETE /me/drive/items/{id}")
    }

    override suspend fun downloadOriginal(cloudId: String): ApiResult<InputStream> {
        TODO("T-103: GET /me/drive/items/{id}/content")
    }

    override suspend fun getThumbnailUrl(cloudId: String): ApiResult<String> {
        TODO("T-103: GET /items/{id}/thumbnails — pre-authorized URL, direct fetch")
    }
}
