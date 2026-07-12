package io.github.pnck.gallery.provider

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

    // Authentication is the AuthManager's job (device flow, ADR-0001), driven by
    // the Settings UI. Providers only make authenticated calls — the shared client's
    // interceptor injects the Bearer token (PRD §8.3).

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

    /**
     * Current cloud metadata (id, content hash, size, dims). Used to *verify* a
     * photo really exists in the cloud with matching content before releasing its
     * local copy (PRD §7.3) — the anti-data-loss guard for "free up space".
     * A non-retryable Error means the file is gone; treat that as "not safe".
     */
    suspend fun getFileMetadata(cloudId: String): ApiResult<CloudFile>

    /** Fresh short-lived thumbnail URL for immediate rendering. */
    suspend fun getThumbnailUrl(cloudId: String): ApiResult<String>

    /**
     * A user-openable web link to the app's backup folder (so the user can browse
     * their uploaded photos directly), or null if it can't be resolved. This is a
     * normal, visible Drive folder — not hidden app-data (we never use appDataFolder).
     */
    suspend fun backupFolderLink(): ApiResult<String?>

    /**
     * The email of the account this session is signed into — so the user can
     * confirm uploads are landing where they expect (diagnoses account mismatch).
     */
    suspend fun getAccountEmail(): ApiResult<String?>
}
