package io.github.pnck.gallery.provider.upload

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/**
 * Streams the byte range [offset, offset+length) of a content:// Uri as an
 * OkHttp request body — one chunk of a Drive resumable upload.
 *
 * Positioning is fd-level (`FileChannel.position` = lseek), NOT
 * `InputStream.skip`: skip's read-and-discard fallback turns a chunked upload
 * into QUADRATIC IO on unseekable providers (a 2 GB file would re-read ~256 GB).
 * An unseekable fd throws immediately on the first chunk instead — see
 * [UploadBatchProcessor]'s pre-flight check, which skips such files permanently.
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
        val pfd = resolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("Cannot open $uri")
        // AutoCloseInputStream owns (and closes) the pfd.
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            input.channel.position(offset) // lseek — IOException here = unseekable provider
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
    }

    private companion object {
        const val SEGMENT = 64L * 1024
    }
}
