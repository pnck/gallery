package io.github.pnck.gallery.data.repo

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.data.db.PhotoEntity
import io.github.pnck.gallery.data.scanner.LocalMediaScanner
import io.github.pnck.gallery.domain.MediaBucket
import io.github.pnck.gallery.domain.MediaTypeFilter
import io.github.pnck.gallery.domain.PhotoDetails
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.domain.SavedCopy
import io.github.pnck.gallery.domain.StorageSummary
import io.github.pnck.gallery.domain.SyncCounts
import io.github.pnck.gallery.domain.SyncFilter
import io.github.pnck.gallery.domain.SyncState
import io.github.pnck.gallery.domain.TimelinePhoto
import io.github.pnck.gallery.domain.TimelineQuery
import io.github.pnck.gallery.domain.TimelineSort
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.ICloudStorageProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed timeline (PRD §9.3) plus the detail-view actions (download-to-cache
 * for viewing, save-to-device). The RemoteMediator that tops the timeline up from
 * the cloud (cursors in sync_keys) is wired in with T-402/T-303.
 */
class PhotoRepositoryImpl(
    private val context: Context,
    private val photoDao: PhotoDao,
    private val provider: ICloudStorageProvider,
    private val resolver: ContentResolver,
) : PhotoRepository {

    override fun getTimeline(query: TimelineQuery): Flow<List<TimelinePhoto>> =
        photoDao.observePhotos(buildTimelineQuery(query)).map { rows -> rows.map { it.toTimelinePhoto() } }

    override suspend fun availableBuckets(): List<MediaBucket> =
        LocalMediaScanner(resolver).listBuckets()

    override fun observeStorageSummary(): Flow<StorageSummary> =
        photoDao.observeStorage().map {
            StorageSummary(
                localBytes = it.localBytes,
                freeableBytes = it.freeableBytes,
                notBackedUpBytes = it.notBackedUpBytes,
                freeableCount = it.freeableCount,
                localCount = it.localCount,
                cloudOnlyCount = it.cloudOnlyCount,
            )
        }

    override fun observePhoto(id: String): Flow<TimelinePhoto?> =
        photoDao.observeById(id).map { it?.toTimelinePhoto() }

    override fun observeSyncCounts(): Flow<SyncCounts> =
        photoDao.observeCounts().map {
            SyncCounts(it.pendingUpload, it.queued, it.synced, it.cloudOnly, it.pendingDelete)
        }

    override suspend fun countWithoutCloud(ids: List<String>): Int =
        withContext(Dispatchers.IO) { photoDao.countWithoutCloud(ids) }

    override suspend fun clearBackupQueue() = withContext(Dispatchers.IO) {
        photoDao.dequeueAll()
    }

    override suspend fun queuedCount(): Int = withContext(Dispatchers.IO) {
        photoDao.queuedCount()
    }

    override suspend fun includeForBackup(ids: List<String>) = withContext(Dispatchers.IO) {
        photoDao.includeForBackup(ids)
    }

    override suspend fun photoDetails(id: String): PhotoDetails? = withContext(Dispatchers.IO) {
        photoDao.getById(id)?.let { row ->
            PhotoDetails(
                id = row.id,
                width = row.width,
                height = row.height,
                dateTakenMs = row.dateTaken,
                syncState = row.syncState,
                localUri = row.localUri,
                cloudId = row.cloudId,
                provider = row.provider,
                contentHashType = row.contentHashType,
                contentHashValue = row.contentHashValue,
                bucketName = row.bucketName,
            )
        }
    }

    /**
     * Fetch the cloud original into cacheDir and expose it as a FileProvider
     * content uri. Cache files are app-private, so MediaStore never rescans them —
     * no duplicate rows (PRD §9.1). Returns the existing cache file immediately if
     * already downloaded.
     *
     * Resumable + atomic: bytes land in a `.part` file; an interrupted download
     * resumes with a Range request from the on-disk length, and the finished file
     * is renamed into place — a killed process can never leave a truncated file
     * that later gets served as complete.
     */
    override suspend fun cacheOriginal(id: String): String? = withContext(Dispatchers.IO) {
        val row = photoDao.getById(id) ?: return@withContext null
        val cloudId = row.cloudId ?: return@withContext null

        val dir = File(context.cacheDir, "originals").apply { mkdirs() }
        val final = File(dir, "$id.jpg")
        if (final.exists() && final.length() > 0) return@withContext fileProviderUri(final).toString()

        val tmp = File(dir, "$id.part")
        val expected = row.sizeBytes.takeIf { it > 0 }
        // A previous attempt may have completed the bytes but died before rename.
        if (expected != null && tmp.length() == expected && tmp.renameTo(final)) {
            return@withContext fileProviderUri(final).toString()
        }

        repeat(MAX_DOWNLOAD_ATTEMPTS) {
            val offset = if (tmp.exists()) tmp.length() else 0L
            when (val res = provider.downloadOriginal(cloudId, offset)) {
                is ApiResult.Success -> {
                    val complete = runCatching {
                        res.data.use { input ->
                            FileOutputStream(tmp, /* append = */ offset > 0).use { out -> input.copyTo(out) }
                        }
                        // OkHttp enforces Content-Length, so a clean return means the
                        // full (remaining) body landed; the size check is the belt.
                        expected == null || tmp.length() == expected
                    }.getOrDefault(false)
                    if (complete && tmp.renameTo(final)) {
                        return@withContext fileProviderUri(final).toString()
                    }
                    // Incomplete/corrupt state we can't resume sanely → start over.
                    if (expected != null && tmp.length() > expected) tmp.delete()
                }
                is ApiResult.Error -> when {
                    // Range refused / offset beyond EOF: the partial is unusable →
                    // drop it and retry once from byte 0.
                    res.code == 416 -> tmp.delete()
                    res.retryable -> Unit // loop resumes from the new on-disk length
                    else -> {
                        tmp.delete()
                        return@withContext null
                    }
                }
            }
        }
        tmp.delete()
        return@withContext null
    }

    /**
     * Download the cloud original and publish it into the shared gallery via
     * MediaStore (Pictures/, scoped storage — never DCIM, invariant #9), then flip
     * the row to SYNCED with the inserted uri. The reconciler dedups on that uri,
     * so the next scan won't create a duplicate PENDING_UPLOAD row.
     */
    override suspend fun saveToDevice(id: String): SavedCopy? = withContext(Dispatchers.IO) {
        val row = photoDao.getById(id) ?: run {
            Log.w(TAG, "saveToDevice: no row for $id")
            return@withContext null
        }
        val cloudId = row.cloudId ?: run {
            Log.w(TAG, "saveToDevice: $id has no cloudId")
            return@withContext null
        }

        Log.i(TAG, "saveToDevice: downloading $cloudId ($id)")
        val stream = when (val res = provider.downloadOriginal(cloudId)) {
            is ApiResult.Success -> res.data
            is ApiResult.Error -> run {
                Log.w(TAG, "saveToDevice: download FAILED code=${res.code} retryable=${res.retryable} — ${res.message}")
                return@withContext null
            }
        }

        // Restore to the ORIGINAL folder and name: the row carries the uploader's
        // source path (appProperties at upload, re-ingested on downstream sync),
        // and the cloud object keeps the original display name.
        val cloudMeta = (provider.getFileMetadata(cloudId) as? ApiResult.Success)?.data
        val displayName = cloudMeta?.name ?: "gallery-$cloudId.jpg"
        val targetFolder = row.relativePath ?: cloudMeta?.sourcePath
            ?: if (row.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

        // Videos restore into the video collection; the images table would reject
        // (or misfile) a video row. Mime comes from the original file extension so
        // mp4/webm/heic etc. all round-trip faithfully.
        val ext = displayName.substringAfterLast('.', "")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: if (row.isVideo) "video/mp4" else "image/jpeg"
        // Round-trip the capture time: Drive's own createdTime is the upload time
        // and EXIF is absent for screenshots — the upload's appProperties copy is
        // the only trustworthy source. Stamped as DATE_TAKEN (timeline ordering)
        // and DATE_MODIFIED.
        val takenMs = cloudMeta?.dateTakenMs?.takeIf { it > 0 } ?: row.dateTaken
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (takenMs > 0) {
                put(MediaStore.MediaColumns.DATE_TAKEN, takenMs)
                put(MediaStore.MediaColumns.DATE_MODIFIED, takenMs / 1000)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, targetFolder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (row.isVideo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        var uri = resolver.insert(collection, values)
        if (uri == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // A STALE row (file removed out-of-band, MediaStore never told) blocks
            // the insert with the same path+name. If its bytes are gone, bury the
            // corpse and retry once. The probe is a content-uri open — no paths.
            Log.w(TAG, "saveToDevice: insert blocked — probing for a stale row at $targetFolder$displayName")
            val stale = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(targetFolder.let { if (it.endsWith('/')) it else "$it/" }, displayName),
                null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            if (stale != null) {
                val staleUri = Uri.withAppendedPath(collection, stale.toString())
                val alive = runCatching { resolver.openInputStream(staleUri)?.close() != null }.getOrDefault(false)
                if (!alive) {
                    resolver.delete(staleUri, null, null)
                    uri = resolver.insert(collection, values)
                }
            }
        }
        if (uri == null) {
            Log.w(TAG, "saveToDevice: MediaStore insert returned null (folder=$targetFolder name=$displayName mime=$mime)")
            stream.close()
            return@withContext null
        }
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out -> stream.use { it.copyTo(out) } } != null
        }.getOrElse {
            Log.w(TAG, "saveToDevice: byte copy FAILED for $displayName — ${it.message}")
            false
        }
        if (!ok) {
            resolver.delete(uri, null, null)
            return@withContext null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        // MediaStore IGNORES DATE_MODIFIED at insert (verified on-device): on the
        // legacy model (≤29) fix the real file mtime so file managers show the
        // capture date. Narrow invariant-#9 exception: _data is read only to
        // stamp our own just-restored file, never to reference media.
        if (takenMs > 0 && Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) File(c.getString(0)).setLastModified(takenMs)
                }
            }
        }

        // The freshly published uri becomes the row's local copy: CLOUD_ONLY → SYNCED.
        // adoptLocalCopy first removes any scan-created fresh row holding this uri
        // (unique localUri index would otherwise reject the update).
        photoDao.adoptLocalCopy(id, uri.toString())
        Log.i(TAG, "saveToDevice: OK → $uri (dateTaken=$takenMs)")
        SavedCopy(uri.toString(), targetFolder.trimEnd('/'))
    }

    override suspend fun localUrisToDelete(ids: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            photoDao.getByIds(ids).mapNotNull { it.localUri }
        }

    override suspend fun purge(ids: List<String>) = withContext(Dispatchers.IO) {
        // Best-effort cloud delete first (a failed cloud delete shouldn't strand the
        // row — the next downstream sync would re-surface it as CLOUD_ONLY anyway).
        photoDao.getByIds(ids).forEach { row ->
            row.cloudId?.let { provider.deleteFile(it) }
        }
        photoDao.deleteByIds(ids)
    }

    /**
     * Only offer photos whose cloud copy is *verified* to exist with matching
     * content (MD5) — the anti-data-loss guard (PRD §7.3). Verification happens
     * BEFORE the local file is deleted; a photo whose cloud copy is missing or
     * whose bytes differ is never offered for freeing. The confirmed hash is
     * persisted so the local/cloud identity survives a broken state machine.
     */
    /**
     * The freeable set is a PURE DB read (owner directive, superseding the old
     * spot re-verification): a SYNCED row in an up-to-date DB is already
     * verified evidence — reconcile hashed the file at upload and cross-checked
     * the cloud listing on every downstream sync. Re-hashing every file at
     * cleanup time was a redundant second verification layer: minutes of
     * silence on big libraries, and a whole class of silent failure modes
     * (SecurityException swallowed by runCatching → "nothing to free"). The
     * up-to-date gate lives at the call site (SpaceManagementViewModel).
     */
    override suspend fun freeableLocalUris(): List<String> = withContext(Dispatchers.IO) {
        photoDao.getSyncedWithLocal().mapNotNull { it.localUri }
    }

    override suspend fun freeableLocalUrisFor(ids: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) emptyList() else photoDao.getSyncedWithLocalByIds(ids).mapNotNull { it.localUri }
        }

    override suspend fun releaseLocalCopies(uris: List<String>) = withContext(Dispatchers.IO) {
        val ids = uris.mapNotNull { photoDao.findByLocalUri(it)?.id }
        if (ids.isNotEmpty()) photoDao.markAsCloudOnly(ids)
    }

    private fun writeStreamToFile(stream: InputStream, file: File): Boolean = runCatching {
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        true
    }.getOrElse {
        file.delete()
        false
    }

    private companion object {
        const val TAG = "gallery-sync"

        /** Bounded resume loop for cacheOriginal (each round resumes from disk). */
        const val MAX_DOWNLOAD_ATTEMPTS = 4
    }

    private fun fileProviderUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Assemble the timeline page query from a closed vocabulary of columns/keywords
     * (never user-provided text). Folder ids are bound as `?` args; cloud-only rows
     * (null bucket) always pass the folder filter since they have no local folder.
     */
    private fun buildTimelineQuery(query: TimelineQuery): SupportSQLiteQuery {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()

        when (query.filter) {
            SyncFilter.ALL -> Unit
            SyncFilter.NOT_BACKED_UP -> where += "syncState = 0"
            SyncFilter.BACKED_UP -> where += "syncState = 1"
            SyncFilter.CLOUD_ONLY -> where += "syncState = 2"
        }
        when (query.mediaType) {
            MediaTypeFilter.ALL -> Unit
            MediaTypeFilter.IMAGES -> where += "isVideo = 0"
            MediaTypeFilter.VIDEOS -> where += "isVideo = 1"
        }
        if (query.bucketIds.isNotEmpty()) {
            val placeholders = query.bucketIds.joinToString(",") { "?" }
            where += "(bucketId IN ($placeholders) OR bucketId IS NULL)"
            args.addAll(query.bucketIds)
        }

        val orderBy = when (query.sort) {
            TimelineSort.DATE_DESC -> "dateTaken DESC"
            TimelineSort.DATE_ASC -> "dateTaken ASC"
            TimelineSort.SIZE_DESC -> "sizeBytes DESC"
            TimelineSort.SIZE_ASC -> "sizeBytes ASC"
        }

        val sql = buildString {
            append("SELECT * FROM photos")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY ").append(orderBy)
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}

/** Anti-corruption mapping (PRD §3.8): the UI never sees PhotoEntity. */
internal fun PhotoEntity.toTimelinePhoto(): TimelinePhoto =
    TimelinePhoto(
        id = id,
        renderUri = localUri ?: "${provider.orEmpty().lowercase()}://$cloudId",
        aspectRatio = if (height > 0) width.toFloat() / height else 1f,
        dateTaken = dateTaken,
        sizeBytes = sizeBytes,
        syncState = syncState,
        localUri = localUri,
        cloudId = cloudId,
        provider = provider,
        excluded = excluded,
        // Unclassified = pending AND never hashed: scan inserts rows like this;
        // reconcile (or the upload path) computes the MD5 and classifies them.
        classified = syncState != SyncState.PENDING_UPLOAD || contentHashValue != null,
        queued = queued,
        uploadAttempts = uploadAttempts,
        isVideo = isVideo,
        durationMs = durationMs,
    )
