package io.github.pnck.gallery.provider

import android.content.Context
import android.net.Uri
import io.github.pnck.gallery.network.ApiResult
import java.io.InputStream

/**
 * The core contract of the cloud proxy layer (PRD §4.1).
 *
 * All methods are suspending and return [ApiResult]; providers own token refresh
 * and exception wrapping internally. See PRD §4.2 for the per-provider mapping table.
 */
interface ICloudStorageProvider {
    val providerType: ProviderType

    /** Launches the AppAuth browser-based authorization flow. */
    suspend fun authenticate(context: Context): ApiResult<Unit>

    /**
     * Initial full listing, page by page.
     * @param pageToken null for the first page; the response carries the next token.
     */
    suspend fun listPhotos(pageToken: String?): ApiResult<CloudPage>

    /**
     * Incremental/downstream sync including server-side deletions (PRD §4.3).
     * NOTE: page tokens are NOT delta tokens — use Drive Changes API / Graph delta.
     * @param deltaToken previously stored cursor (Drive startPageToken / Graph deltaLink)
     */
    suspend fun fetchChanges(deltaToken: String?): ApiResult<CloudChangeSet>

    /** Upload; implementation picks multipart vs resumable/session by size (PRD §4.4). */
    suspend fun uploadFile(
        uri: Uri,
        mimeType: String,
        onProgress: (Int) -> Unit,
    ): ApiResult<CloudFile>

    suspend fun deleteFile(cloudId: String): ApiResult<Unit>

    /** Original bytes for the detail view — cache to cacheDir, never DCIM (PRD §9.1). */
    suspend fun downloadOriginal(cloudId: String): ApiResult<InputStream>

    /** Fresh short-lived thumbnail URL for immediate rendering. */
    suspend fun getThumbnailUrl(cloudId: String): ApiResult<String>
}
