package io.github.pnck.gallery.provider.upload

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/**
 * Streams a content:// Uri as an OkHttp request body with progress reporting.
 * Re-opens the stream on every attempt, so OkHttp retries stay correct.
 */
class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val mimeType: String,
    private val onProgress: (percent: Int) -> Unit = {},
) : RequestBody() {

    private val length: Long by lazy {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L
    }

    override fun contentType(): MediaType? = mimeType.toMediaTypeOrNull()

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri)
            ?: throw FileNotFoundException("Cannot open $uri")
        input.source().use { source ->
            var written = 0L
            var lastPercent = -1
            while (true) {
                val read = source.read(sink.buffer, SEGMENT)
                if (read == -1L) break
                sink.emitCompleteSegments()
                written += read
                if (length > 0) {
                    val percent = ((written * 100) / length).toInt().coerceAtMost(100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
            }
        }
    }

    private companion object {
        const val SEGMENT = 8L * 1024
    }
}
