package io.github.pnck.gallery.data.sync

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.data.db.PhotoEntity
import io.github.pnck.gallery.data.scanner.LocalMediaScanner
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.domain.SyncState
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.CloudFile
import io.github.pnck.gallery.provider.ContentHash
import io.github.pnck.gallery.provider.ICloudStorageProvider
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "gallery-sync"

/**
 * Reconcile-from-truth (the reliability core, per the backup > sync > album priority).
 *
 * The Room DB is treated as a pure CACHE: this pass rebuilds the four-state
 * classification from ground truth — the full cloud folder listing and the full local
 * MediaStore scan — matched by content hash (Drive MD5), the only cross-side identity.
 *
 *   SYNCED        = a local file whose MD5 is also in the cloud
 *   PENDING_UPLOAD = a local file whose MD5 is NOT in the cloud (default: re-upload)
 *   CLOUD_ONLY     = a cloud file with no matching local file
 *
 * It PRUNES rows that match neither truth (phantoms, stale duplicates), so a broken
 * state machine self-heals on the next run. It is deliberately conservative for
 * BACKUP safety:
 *  - it never runs against a partial cloud listing (a transient cloud error aborts
 *    WITHOUT pruning — a network blip must never delete rows);
 *  - it only edits DB rows, never files (no MediaStore/cloud deletes);
 *  - a local file whose hash can't be computed stays PENDING_UPLOAD (re-upload) —
 *    never silently assumed backed up;
 *  - PENDING_DELETE rows are tombstones: the user's delete intent is never resurrected
 *    (deletion intent is the one thing content-reconciliation can't reconstruct).
 *
 * MD5 is cached on the row and reused while [PhotoEntity.sizeBytes] +
 * [PhotoEntity.dateModifiedSec] are unchanged, so repeat runs only hash new/changed
 * files (honouring the "don't hash on every scan" performance rule).
 */
class ReconcileProcessor(
    private val provider: ICloudStorageProvider,
    private val photoDao: PhotoDao,
    private val scanner: LocalMediaScanner,
    private val settings: AppSettingsStore,
    private val resolver: ContentResolver,
) {
    sealed interface Outcome {
        data class Done(val synced: Int, val pendingUpload: Int, val cloudOnly: Int, val pruned: Int) : Outcome

        /** A transient cloud error — nothing was changed; retry later. */
        data object Retry : Outcome
    }

    suspend fun reconcile(): Outcome = withContext(Dispatchers.IO) {
        // 1. FULL cloud truth. Abort (Retry) on any error so we never prune against a
        //    partial listing — backup safety outranks freshness.
        val cloud = mutableListOf<CloudFile>()
        var pageToken: String? = null
        do {
            when (val res = provider.listPhotos(pageToken)) {
                is ApiResult.Success -> {
                    cloud += res.data.files
                    pageToken = res.data.nextPageToken
                }
                is ApiResult.Error -> {
                    Log.w(TAG, "reconcile: cloud list failed (retryable=${res.retryable}) — no changes made")
                    return@withContext Outcome.Retry
                }
            }
        } while (pageToken != null)

        // 2. FULL local truth (scan allowlist honoured), with cached-or-computed MD5.
        val allowed = settings.scanBuckets.first()
        val scanned = scanner.scanIncremental(0L).let { items ->
            if (allowed.isEmpty()) items else items.filter { it.bucketId in allowed }
        }
        val existing = photoDao.getAllRows()
        val cachedHash: Map<String, PhotoEntity> = existing.filter { it.localUri != null && it.contentHashValue != null }
            .associateBy { it.localUri!! }

        val local = scanned.map { item ->
            val cache = cachedHash[item.contentUri]
            val md5 = if (cache != null && cache.sizeBytes == item.sizeBytes && cache.dateModifiedSec == item.dateModifiedSec) {
                cache.contentHashValue
            } else {
                computeMd5(item.contentUri) // null on read error → stays PENDING_UPLOAD
            }
            LocalTruth(
                uri = item.contentUri,
                md5 = md5,
                dateTaken = item.dateTakenMs,
                dateModifiedSec = item.dateModifiedSec,
                width = item.width,
                height = item.height,
                sizeBytes = item.sizeBytes,
                bucketId = item.bucketId,
                bucketName = item.bucketName,
            )
        }

        val cloudTruth = cloud.map {
            CloudTruth(
                cloudId = it.id,
                md5 = (it.contentHash as? ContentHash.Md5)?.value,
                dateTaken = it.creationTime,
                width = it.width,
                height = it.height,
                thumbnailUrl = it.thumbnailUrl,
            )
        }

        // 3. Pure derivation, 4. apply.
        val plan = planReconcile(local, cloudTruth, existing, provider.providerType.name)
        if (plan.upserts.isNotEmpty()) photoDao.upsertAll(plan.upserts)
        if (plan.deleteIds.isNotEmpty()) photoDao.deleteByIds(plan.deleteIds)

        val synced = plan.upserts.count { it.syncState == SyncState.SYNCED }
        val pending = plan.upserts.count { it.syncState == SyncState.PENDING_UPLOAD }
        val cloudOnly = plan.upserts.count { it.syncState == SyncState.CLOUD_ONLY }
        Log.i(TAG, "reconcile: synced=$synced pendingUpload=$pending cloudOnly=$cloudOnly pruned=${plan.deleteIds.size}")
        Outcome.Done(synced, pending, cloudOnly, plan.deleteIds.size)
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
}

/** Local ground-truth item for [planReconcile] (a MediaStore image + its MD5). */
data class LocalTruth(
    val uri: String,
    val md5: String?,
    val dateTaken: Long,
    val dateModifiedSec: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val bucketId: String?,
    val bucketName: String?,
)

/** Cloud ground-truth item for [planReconcile] (a backup-folder file + its MD5). */
data class CloudTruth(
    val cloudId: String,
    val md5: String?,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val thumbnailUrl: String?,
)

/** The DB mutation the reconcile computed: rows to write, row ids to delete. */
data class ReconcilePlan(val upserts: List<PhotoEntity>, val deleteIds: List<String>)

/**
 * Pure classification of local + cloud truth into DB rows (no I/O — unit tested).
 *
 * Identity is MD5. Each cloud file is matched to at most one local file (by MD5),
 * making that local file SYNCED and consuming the cloud file; unmatched local files
 * are PENDING_UPLOAD; unmatched cloud files are CLOUD_ONLY. Existing rows are reused
 * (id/excluded preserved) where they map to the same local uri or cloud id. Every
 * existing row that neither truth accounts for is pruned — EXCEPT PENDING_DELETE
 * tombstones, which are always preserved (the user's delete intent).
 */
fun planReconcile(
    local: List<LocalTruth>,
    cloud: List<CloudTruth>,
    existing: List<PhotoEntity>,
    providerName: String,
): ReconcilePlan {
    val existingByUri = existing.filter { it.localUri != null }.associateBy { it.localUri!! }
    val existingByCloudId = existing.filter { it.cloudId != null }.associateBy { it.cloudId!! }
    val tombstoneCloudIds = existing.filter { it.syncState == SyncState.PENDING_DELETE && it.cloudId != null }
        .mapNotNull { it.cloudId }.toSet()

    val cloudByMd5: Map<String, List<CloudTruth>> = cloud.filter { it.md5 != null }.groupBy { it.md5!! }
    val consumedCloudIds = mutableSetOf<String>()
    val consumedRowIds = mutableSetOf<String>()
    val upserts = mutableListOf<PhotoEntity>()

    // A. Every local file becomes a row. SYNCED if its bytes are in the cloud, else
    //    PENDING_UPLOAD (default: back it up). Tombstoned rows are left untouched.
    for (l in local) {
        val row = existingByUri[l.uri]
        if (row != null && row.syncState == SyncState.PENDING_DELETE) {
            consumedRowIds += row.id
            continue
        }
        val match = l.md5?.let { md5 -> cloudByMd5[md5]?.firstOrNull { it.cloudId !in consumedCloudIds } }
        if (match != null) consumedCloudIds += match.cloudId
        if (row != null) consumedRowIds += row.id
        upserts += PhotoEntity(
            id = row?.id ?: UUID.randomUUID().toString(),
            localUri = l.uri,
            cloudId = match?.cloudId ?: row?.cloudId?.takeIf { match == null && l.md5 == null },
            provider = if (match != null) providerName else row?.provider,
            contentHashType = if (l.md5 != null) "MD5" else row?.contentHashType,
            contentHashValue = l.md5 ?: row?.contentHashValue,
            cloudThumbnailUrl = match?.thumbnailUrl ?: row?.cloudThumbnailUrl,
            dateTaken = l.dateTaken,
            dateModifiedSec = l.dateModifiedSec,
            width = l.width,
            height = l.height,
            sizeBytes = l.sizeBytes,
            bucketId = l.bucketId,
            bucketName = l.bucketName,
            syncState = if (match != null) SyncState.SYNCED else SyncState.PENDING_UPLOAD,
            excluded = row?.excluded ?: false,
        )
    }

    // B. Cloud files not claimed by a local match are CLOUD_ONLY (still backed up,
    //    no local copy). Tombstoned cloud ids are left for the delete flow.
    for (c in cloud) {
        if (c.cloudId in consumedCloudIds) continue
        val row = existingByCloudId[c.cloudId]
        if (c.cloudId in tombstoneCloudIds) {
            if (row != null) consumedRowIds += row.id
            continue
        }
        if (row != null) consumedRowIds += row.id
        upserts += PhotoEntity(
            id = row?.id ?: UUID.randomUUID().toString(),
            localUri = null,
            cloudId = c.cloudId,
            provider = providerName,
            contentHashType = if (c.md5 != null) "MD5" else null,
            contentHashValue = c.md5,
            cloudThumbnailUrl = c.thumbnailUrl ?: row?.cloudThumbnailUrl,
            dateTaken = c.dateTaken,
            dateModifiedSec = 0,
            width = c.width,
            height = c.height,
            sizeBytes = row?.sizeBytes ?: 0,
            bucketId = null,
            bucketName = null,
            syncState = SyncState.CLOUD_ONLY,
            excluded = row?.excluded ?: false,
        )
    }

    // C. Anything neither truth accounts for is stale (phantom / vanished). Keep
    //    tombstones; the file itself is never touched here, so a mistaken prune just
    //    re-appears as PENDING_UPLOAD on the next scan — never data loss.
    val deleteIds = existing
        .filter { it.id !in consumedRowIds && it.syncState != SyncState.PENDING_DELETE }
        .map { it.id }

    return ReconcilePlan(upserts, deleteIds)
}
