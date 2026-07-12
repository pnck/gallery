package io.github.pnck.gallery.ui.coil

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
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
 * PhotoRepositoryImpl.toTimelinePhoto). This fetcher resolves that to a fresh,
 * short-lived thumbnail URL via the provider, then pulls the bytes through the
 * shared OkHttpClient — so the download rides the transport tunnel and the
 * GoogleAuthInterceptor injects `Authorization: Bearer` for Drive thumbnail hosts
 * (googleusercontent.com). Local photos keep using Coil's built-in content:// path.
 */
class ProviderUriFetcher(
    private val cloudId: String,
    private val options: Options,
    private val provider: ICloudStorageProvider,
    private val client: OkHttpClient,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val url = when (val res = provider.getThumbnailUrl(cloudId)) {
            is ApiResult.Success -> res.data ?: error("no thumbnail for $cloudId")
            is ApiResult.Error -> error("thumbnail ${res.code} for $cloudId")
        }
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        val body = response.body ?: run {
            response.close()
            error("empty thumbnail body for $cloudId")
        }
        SourceFetchResult(
            source = ImageSource(source = body.source(), fileSystem = options.fileSystem),
            mimeType = body.contentType()?.toString(),
            dataSource = DataSource.NETWORK,
        )
    }

    /** Handles the `g_drive://` / `one_drive://` schemes; delegates everything else. */
    class Factory(
        private val provider: ICloudStorageProvider,
        private val client: OkHttpClient,
    ) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            when (data.scheme) {
                "g_drive", "one_drive" -> Unit
                else -> return null
            }
            val cloudId = data.authority?.takeIf { it.isNotEmpty() } ?: return null
            return ProviderUriFetcher(cloudId, options, provider, client)
        }
    }
}
