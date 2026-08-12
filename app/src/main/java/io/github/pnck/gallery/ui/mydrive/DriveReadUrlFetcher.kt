package io.github.pnck.gallery.ui.mydrive

import coil3.ImageLoader
import io.github.pnck.gallery.provider.driveread.DriveReadAccess
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Coil model + fetcher for "My Drive" images: any Drive URL (thumbnail or full
 * `alt=media`) loaded with the SEPARATE drive.readonly token, not the backup token the
 * shared loader injects. Coil selects this fetcher by the [DriveReadUrl] model type, so
 * backup thumbnails are unaffected.
 */
data class DriveReadUrl(val url: String)

class DriveReadUrlFetcher(
    private val model: DriveReadUrl,
    private val options: Options,
    private val access: DriveReadAccess,
    private val client: OkHttpClient,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val token = access.validToken() ?: error("Drive read access expired")
        val response = client.newCall(
            Request.Builder().url(model.url).header("Authorization", "Bearer $token").build(),
        ).execute()
        // OkHttp 5: Response.body is non-null (empty body = 0-byte ResponseBody).
        val body = response.body
        SourceFetchResult(
            source = ImageSource(source = body.source(), fileSystem = options.fileSystem),
            mimeType = body.contentType()?.toString(),
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(
        private val access: DriveReadAccess,
        private val client: OkHttpClient,
    ) : Fetcher.Factory<DriveReadUrl> {
        override fun create(data: DriveReadUrl, options: Options, imageLoader: ImageLoader): Fetcher =
            DriveReadUrlFetcher(data, options, access, client)
    }
}
