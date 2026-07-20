package io.github.pnck.gallery.provider.upload

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/**
 * Streams the byte range [offset, offset+length) of a content:// Uri as an
 * OkHttp request body — one chunk of a Drive resumable upload. The stream is
 * re-opened per attempt, so chunk retries stay correct.
 */
class ContentUriRangeRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val offset: Long,
    private val length: Long,
    private val mimeType: String,
) : RequestBody() {

    init {
        require(offset >= 0 && length > 0) { "bad range offset=$offset length=$length" }
    }

    override fun contentType(): MediaType? = mimeType.toMediaTypeOrNull()

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri)
            ?: throw FileNotFoundException("Cannot open $uri")
        // Skip to the chunk start. InputStream.skip may short-read, loop it; fall
        // back to read-discard for providers that don't support skip.
        var skipped = 0L
        while (skipped < offset) {
            val n = input.skip(offset - skipped)
            skipped += if (n > 0) {
                n
            } else {
                val b = ByteArray(minOf(64 * 1024L, offset - skipped).toInt())
                val r = input.read(b)
                if (r < 0) throw IOException("stream shorter than offset $offset")
                r.toLong()
            }
        }
        input.source().use { source ->
            var remaining = length
            while (remaining > 0) {
                val read = source.read(sink.buffer, minOf(SEGMENT, remaining))
                if (read == -1L) throw IOException("stream ended early at $remaining bytes left")
                sink.emitCompleteSegments()
                remaining -= read
            }
        }
    }

    private companion object {
        const val SEGMENT = 64L * 1024
    }
}
