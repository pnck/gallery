package io.github.pnck.gallery.ui.detail

import android.content.Context
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import io.github.pnck.gallery.domain.PhotoDetails
import io.github.pnck.gallery.domain.SyncState
import kotlin.math.roundToInt

/**
 * UI model for the photo-details panel (T-403). Combines DB-known metadata
 * (always available) with EXIF read from the local file (present only when a
 * local copy exists — CLOUD_ONLY photos show what the DB knows).
 */
data class PhotoInfo(
    val width: Int,
    val height: Int,
    val dateTakenMs: Long,
    val syncState: SyncState,
    val provider: String?,
    val sizeBytes: Long?,
    val cameraMake: String?,
    val cameraModel: String?,
    val aperture: String?,
    val exposure: String?,
    val iso: String?,
    val focalLength: String?,
    val latLon: Pair<Double, Double>?,
    val contentHash: String?,
)

/** Reads EXIF + file size from a local content uri; JVM-Android util, off-main. */
object ExifReader {

    fun build(context: Context, details: PhotoDetails): PhotoInfo {
        val resolver = context.contentResolver
        val uri = details.localUri?.toUri()

        val size = uri?.let { u ->
            runCatching {
                resolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
            }.getOrNull()
        }

        val exif = uri?.let { u ->
            runCatching { resolver.openInputStream(u)?.use { ExifInterface(it) } }.getOrNull()
        }

        return PhotoInfo(
            width = details.width,
            height = details.height,
            dateTakenMs = details.dateTakenMs,
            syncState = details.syncState,
            provider = details.provider,
            sizeBytes = size,
            cameraMake = exif?.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.ifBlank { null },
            cameraModel = exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.ifBlank { null },
            aperture = exif?.getAttribute(ExifInterface.TAG_F_NUMBER)?.toFloatOrNull()?.let { "f/$it" },
            exposure = exif?.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let(::formatShutter),
            iso = (exif?.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                ?: exif?.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS))?.let { "ISO $it" },
            focalLength = exif?.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let(::formatFocal),
            latLon = exif?.latLong?.let { it[0].toDouble() to it[1].toDouble() },
            contentHash = details.contentHashValue?.let { "${details.contentHashType ?: "?"}:$it" },
        )
    }

    private fun formatShutter(seconds: Double): String =
        if (seconds >= 1.0) "${seconds}s" else "1/${(1.0 / seconds).roundToInt()}s"

    /** EXIF focal length is a "num/den" rational. */
    private fun formatFocal(raw: String): String? {
        val mm = if (raw.contains('/')) {
            val (n, d) = raw.split('/').map { it.toDoubleOrNull() ?: return null }
            if (d == 0.0) return null else n / d
        } else {
            raw.toDoubleOrNull() ?: return null
        }
        return "${mm.roundToInt()}mm"
    }
}
