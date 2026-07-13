package io.github.pnck.gallery.data.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import io.github.pnck.gallery.domain.MediaBucket
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
    /** File size in bytes (MediaStore.SIZE). */
    val sizeBytes: Long,
    /** MediaStore BUCKET_ID of the containing folder, or null on OEMs that omit it. */
    val bucketId: String?,
    /** Folder display name (BUCKET_DISPLAY_NAME). */
    val bucketName: String?,
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
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
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
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

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
                        sizeBytes = cursor.getLong(sizeCol),
                        bucketId = cursor.getString(bucketIdCol),
                        bucketName = cursor.getString(bucketNameCol),
                    )
                }
            }
            items
        }

    /**
     * Enumerate the device's image folders (MediaStore buckets) with a photo count,
     * for the scan-allowlist / directory picker. One row per image is scanned and
     * grouped in memory — cheap enough for a settings action and portable across OEMs
     * that don't support GROUP BY on the MediaStore provider.
     */
    suspend fun listBuckets(): List<MediaBucket> = withContext(Dispatchers.IO) {
        // RELATIVE_PATH ("DCIM/Camera/") is API 29+; below that fall back to the parent
        // directory of the deprecated DATA column so same-named folders stay distinguishable.
        val usePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        @Suppress("DEPRECATION")
        val pathCol = if (usePath) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val acc = LinkedHashMap<String, BucketAcc>()
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, pathCol),
            null,
            null,
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pCol = cursor.getColumnIndexOrThrow(pathCol)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol) ?: bucketId
                val existing = acc[bucketId]
                if (existing == null) {
                    val raw = cursor.getString(pCol)
                    val path = if (usePath) raw?.trimEnd('/') else raw?.substringBeforeLast('/', "")?.ifBlank { null }
                    acc[bucketId] = BucketAcc(name, path, 1)
                } else {
                    existing.count++
                }
            }
        }
        acc.map { (id, b) -> MediaBucket(id, b.name, b.path, b.count) }
            .sortedByDescending { it.count }
    }

    private class BucketAcc(val name: String, val path: String?, var count: Int)
}
