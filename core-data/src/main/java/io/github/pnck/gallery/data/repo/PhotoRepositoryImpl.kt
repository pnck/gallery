package io.github.pnck.gallery.data.repo

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.data.db.PhotoEntity
import io.github.pnck.gallery.domain.PhotoDetails
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.domain.SyncCounts
import io.github.pnck.gallery.domain.SyncState
import io.github.pnck.gallery.domain.TimelinePhoto
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.ContentHash
import io.github.pnck.gallery.provider.ICloudStorageProvider
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
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

    override fun getPagedTimelinePhotos(): Flow<PagingData<TimelinePhoto>> =
        Pager(
            config = PagingConfig(pageSize = 90, enablePlaceholders = true),
            pagingSourceFactory = { photoDao.getPhotosPaged() },
        ).flow.map { paging -> paging.map { it.toTimelinePhoto() } }

    override fun getTimeline(): Flow<List<TimelinePhoto>> =
        photoDao.observeAllPhotos().map { rows -> rows.map { it.toTimelinePhoto() } }

    override fun observePhoto(id: String): Flow<TimelinePhoto?> =
        photoDao.observeById(id).map { it?.toTimelinePhoto() }

    override fun observeSyncCounts(): Flow<SyncCounts> =
        photoDao.observeCounts().map {
            SyncCounts(it.pendingUpload, it.synced, it.cloudOnly, it.pendingDelete)
        }

    override suspend fun countWithoutCloud(ids: List<String>): Int =
        withContext(Dispatchers.IO) { photoDao.countWithoutCloud(ids) }

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
            )
        }
    }

    /**
     * Fetch the cloud original into cacheDir and expose it as a FileProvider
     * content uri. Cache files are app-private, so MediaStore never rescans them —
     * no duplicate rows (PRD §9.1). Returns the existing cache file immediately if
     * already downloaded.
     */
    override suspend fun cacheOriginal(id: String): String? = withContext(Dispatchers.IO) {
        val row = photoDao.getById(id) ?: return@withContext null
        val cloudId = row.cloudId ?: return@withContext null

        val dir = File(context.cacheDir, "originals").apply { mkdirs() }
        val file = File(dir, "$id.jpg")
        if (!file.exists() || file.length() == 0L) {
            val stream = when (val res = provider.downloadOriginal(cloudId)) {
                is ApiResult.Success -> res.data
                is ApiResult.Error -> return@withContext null
            }
            if (!writeStreamToFile(stream, file)) return@withContext null
        }
        fileProviderUri(file).toString()
    }

    /**
     * Download the cloud original and publish it into the shared gallery via
     * MediaStore (Pictures/, scoped storage — never DCIM, invariant #9), then flip
     * the row to SYNCED with the inserted uri. The reconciler dedups on that uri,
     * so the next scan won't create a duplicate PENDING_UPLOAD row.
     */
    override suspend fun saveToDevice(id: String): String? = withContext(Dispatchers.IO) {
        val row = photoDao.getById(id) ?: return@withContext null
        val cloudId = row.cloudId ?: return@withContext null

        val stream = when (val res = provider.downloadOriginal(cloudId)) {
            is ApiResult.Success -> res.data
            is ApiResult.Error -> return@withContext null
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "gallery-$cloudId.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android's default Pictures folder (scoped storage, not DCIM — invariant #9).
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: return@withContext null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out -> stream.use { it.copyTo(out) } } != null
        }.getOrDefault(false)
        if (!ok) {
            resolver.delete(uri, null, null)
            return@withContext null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        // The freshly published uri becomes the row's local copy: CLOUD_ONLY → SYNCED.
        photoDao.markAsSyncedWithLocal(id, uri.toString())
        uri.toString()
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
    override suspend fun freeableLocalUris(): List<String> = withContext(Dispatchers.IO) {
        photoDao.getSyncedWithLocal().mapNotNull { row ->
            val cloudId = row.cloudId ?: return@mapNotNull null
            val localUri = row.localUri ?: return@mapNotNull null
            val cloudMd5 = when (val res = provider.getFileMetadata(cloudId)) {
                is ApiResult.Success -> (res.data.contentHash as? ContentHash.Md5)?.value
                is ApiResult.Error -> null // cloud gone/unreachable → not safe to free
            } ?: return@mapNotNull null
            val localMd5 = computeMd5(localUri) ?: return@mapNotNull null
            if (localMd5.equals(cloudMd5, ignoreCase = true)) {
                photoDao.setContentHash(row.id, "MD5", cloudMd5)
                localUri
            } else {
                null
            }
        }
    }

    override suspend fun releaseLocalCopies(uris: List<String>) = withContext(Dispatchers.IO) {
        val ids = uris.mapNotNull { photoDao.findByLocalUri(it)?.id }
        if (ids.isNotEmpty()) photoDao.markAsCloudOnly(ids)
    }

    private fun computeMd5(uriStr: String): String? = runCatching {
        val digest = MessageDigest.getInstance("MD5")
        val ok = resolver.openInputStream(Uri.parse(uriStr))?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            true
        } ?: false
        if (ok) digest.digest().joinToString("") { "%02x".format(it) } else null
    }.getOrNull()

    private fun writeStreamToFile(stream: InputStream, file: File): Boolean = runCatching {
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        true
    }.getOrElse {
        file.delete()
        false
    }

    private fun fileProviderUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** Anti-corruption mapping (PRD §3.8): the UI never sees PhotoEntity. */
internal fun PhotoEntity.toTimelinePhoto(): TimelinePhoto =
    TimelinePhoto(
        id = id,
        renderUri = localUri ?: "${provider.orEmpty().lowercase()}://$cloudId",
        aspectRatio = if (height > 0) width.toFloat() / height else 1f,
        syncState = syncState,
        localUri = localUri,
        cloudId = cloudId,
        provider = provider,
    )
