package io.github.pnck.gallery.ui.coil

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.ICloudStorageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Coil fetcher for CLOUD_ONLY grid thumbnails (T-401, PRD §8.3).
 *
 * The timeline renders such photos as `{provider}://{cloudId}` (see
 * PhotoRepositoryImpl.toTimelinePhoto). Resolution order:
 *  1. The PERSISTED cloudThumbnailUrl (written by downstream sync / reconcile) —
 *     zero extra requests in the common case. Drive thumbnailLinks expire after
 *     a few hours, so a failure (403/404/410) is treated as expiry, not error.
 *  2. On expiry (or no stored URL): ONE metadata GET for a fresh URL, persisted
 *     back so the next cells don't repeat it.
 * Before this, every cold cell did a metadata GET + image GET (2N requests per
 * grid) through the tunnel — scrolling a cloud album looked broken.
 *
 * The bytes ride the shared OkHttpClient — transport tunnel + Bearer injection
 * for googleusercontent.com (PRD §8.3). Local photos keep Coil's content:// path.
 */
class ProviderUriFetcher(
    private val cloudId: String,
    private val options: Options,
    private val provider: ICloudStorageProvider,
    private val photoDao: PhotoDao,
    private val client: OkHttpClient,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val stored = photoDao.findByCloudId(cloudId)?.cloudThumbnailUrl
        if (stored != null) {
            try {
                return@withContext fetchBytes(stored.withSize(GRID_THUMB_PX))
            } catch (_: ThumbnailExpiredException) {
                // fall through to a fresh URL
            }
        }
        val fresh = when (val res = provider.getThumbnailUrl(cloudId)) {
            is ApiResult.Success -> res.data ?: error("no thumbnail for $cloudId")
            is ApiResult.Error -> error("thumbnail ${res.code} for $cloudId")
        }
        photoDao.updateCloudThumbnailUrl(cloudId, fresh)
        fetchBytes(fresh.withSize(GRID_THUMB_PX))
    }

    private class ThumbnailExpiredException : Exception()

    private fun fetchBytes(url: String): SourceFetchResult {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (response.code in EXPIRED_CODES) {
            response.close()
            throw ThumbnailExpiredException()
        }
        val body = response.body ?: run {
            response.close()
            error("empty thumbnail body for $cloudId (${response.code})")
        }
        return SourceFetchResult(
            source = ImageSource(source = body.source(), fileSystem = options.fileSystem),
            mimeType = body.contentType()?.toString(),
            dataSource = DataSource.NETWORK,
        )
    }

    /** Handles the `g_drive://` / `one_drive://` schemes; delegates everything else. */
    class Factory(
        private val provider: ICloudStorageProvider,
        private val photoDao: PhotoDao,
        private val client: OkHttpClient,
    ) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            when (data.scheme) {
                "g_drive", "one_drive" -> Unit
                else -> return null
            }
            val cloudId = data.authority?.takeIf { it.isNotEmpty() } ?: return null
            return ProviderUriFetcher(cloudId, options, provider, photoDao, client)
        }
    }

    private companion object {
        /** Google thumbnail URLs are signed/expiring; these mean "get a fresh one". */
        val EXPIRED_CODES = setOf(403, 404, 410)

        /** Grid cell target size — Drive thumbnailLinks accept a =sNNN size suffix. */
        const val GRID_THUMB_PX = 512

        /** Append/replace the size parameter on a Drive thumbnailLink. */
        fun String.withSize(px: Int): String =
            if (contains("googleusercontent.com")) {
                substringBeforeLast("=") + "=s$px"
            } else {
                this
            }
    }
}
