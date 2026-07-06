package io.github.pnck.gallery.data.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A row from MediaStore, pre-reconciliation (PRD §6.1). */
data class LocalMediaItem(
    val mediaStoreId: Long,
    /** content:// Uri as String — absolute paths are forbidden (scoped storage). */
    val contentUri: String,
    val dateTakenMs: Long,
    val dateModifiedSec: Long,
    val width: Int,
    val height: Int,
)

/**
 * Incremental MediaStore scanner (PRD §6.1).
 *
 * Reconciliation against Room (insert as PENDING_UPLOAD when unknown) is done by
 * the caller; this class only queries. Incremental filter uses DATE_MODIFIED to
 * avoid full-table scans on every wake-up.
 */
class LocalMediaScanner(private val resolver: ContentResolver) {

    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
    )

    suspend fun scanIncremental(sinceDateModifiedSec: Long): List<LocalMediaItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<LocalMediaItem>()
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.DATE_MODIFIED} > ?",
                arrayOf(sinceDateModifiedSec.toString()),
                "${MediaStore.Images.Media.DATE_MODIFIED} ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    // DATE_TAKEN can be 0/null on some OEMs — fall back to DATE_ADDED (sec → ms).
                    val taken = cursor.getLong(takenCol).takeIf { it > 0 }
                        ?: cursor.getLong(addedCol) * 1000
                    items += LocalMediaItem(
                        mediaStoreId = id,
                        contentUri = uri.toString(),
                        dateTakenMs = taken,
                        dateModifiedSec = cursor.getLong(modifiedCol),
                        width = cursor.getInt(widthCol),
                        height = cursor.getInt(heightCol),
                    )
                }
            }
            items
        }
}
