package io.github.pnck.gallery.data.sync

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
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
    private val appContext: Context,
) {
    sealed interface Outcome {
        data class Done(val synced: Int, val pendingUpload: Int, val cloudOnly: Int, val pruned: Int) : Outcome

        /** A transient cloud error — nothing was changed; retry later. */
        data object Retry : Outcome

        /**
         * Preconditions not met (no media-read permission, or the first local scan
         * hasn't completed) — nothing was changed and there is nothing to retry:
         * the scan pipeline will make the next run possible.
         */
        data object Skipped : Outcome

        /**
         * The local scan came back EMPTY while local-backed rows exist (guard 3):
         * the definitive signature of degraded media access (MIUI foreground-only
         * app-op, OEM denial) — readable by the PERIODIC worker as proof that
         * background scans are blind, which no permission API can report.
         */
        data object BlindScan : Outcome
    }

    suspend fun reconcile(): Outcome = withContext(Dispatchers.IO) {
        // Guard 1 — LOCAL truth must exist before any cloud ingestion: filling the
        // DB cloud-first makes the timeline show only cloud-only photos and the
        // local library look lost (the fresh-install report). Skip, don't retry —
        // the scan pipeline sets the flag and kicks us again.
        if (!settings.initialScanDone.first()) {
            Log.i(TAG, "reconcile: skipped — initial local scan not done yet")
            return@withContext Outcome.Skipped
        }
        // Guard 2 — never classify against a BLIND local scan: without the media
        // permission the local "truth" is an empty list and step C would prune
        // every local-backed row (SYNCED badges lost). The cloud side has the same
        // rule already (abort on partial listing); symmetry demands it here.
        if (!hasMediaReadPermission()) {
            Log.i(TAG, "reconcile: skipped — no media read permission")
            return@withContext Outcome.Skipped
        }

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
                relativePath = item.relativePath,
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
                sourcePath = it.sourcePath,
            )
        }

        // 3. Pure derivation, 4. apply.
        // Guard 3 — a (near-)EMPTY local truth while local-backed rows exist is
        // never a real "user deleted everything": it's a blind scan (storage
        // unmounted, MediaStore mid-reindex, foreground-restricted app-op, OEM
        // query quirk — on 2026-07-27 it pruned 943 rows in one pass; on
        // 2026-08-03 a MIUI foreground-only grant returned 1 of 1087). The cloud
        // side already refuses to prune on a partial listing; the local side
        // refuses below 5% of the known library. BlindScan is the degradation
        // EVIDENCE the periodic worker records (the UI's warning row).
        Log.i(TAG, "reconcile: truth local=${local.size} cloud=${cloudTruth.size} existing=${existing.size}")
        val localBacked = existing.count { it.localUri != null }
        if (localBacked > 0 && local.size * 20 < localBacked) {
            Log.w(TAG, "reconcile: ABORT — local scan blind (${local.size} of $localBacked local rows); not pruning")
            return@withContext Outcome.BlindScan
        }
        val plan = planReconcile(local, cloudTruth, existing, provider.providerType.name)
        // Guard 4 — cap mass-pruning: deleting local-backed rows for more than half
        // the library in ONE pass is never a legitimate cleanup, it's a partially
        // blind scan (MediaStore mid-reindex returns a subset, SD card unmounted).
        // Linger is cheap (next reconcile self-heals); a wrong mass-prune is the
        // "all my badges died" report.
        val existingById = existing.associateBy { it.id }
        val localBackedDeletes = plan.deleteIds.count { existingById[it]?.localUri != null }
        if (localBackedDeletes > localBacked / 2 && localBackedDeletes > MASS_PRUNE_MIN) {
            Log.w(
                TAG,
                "reconcile: ABORT — would prune $localBackedDeletes of $localBacked local-backed rows",
            )
            return@withContext Outcome.Skipped
        }
        // Atomic, prune-before-upsert: re-linking a local row to a cloudId that a
        // to-be-pruned phantom still holds must not hit UNIQUE(provider, cloudId).
        photoDao.applyReconcilePlan(plan.upserts, plan.deleteIds)

        val synced = plan.upserts.count { it.syncState == SyncState.SYNCED }
        val pending = plan.upserts.count { it.syncState == SyncState.PENDING_UPLOAD }
        val cloudOnly = plan.upserts.count { it.syncState == SyncState.CLOUD_ONLY }
        Log.i(TAG, "reconcile: synced=$synced pendingUpload=$pending cloudOnly=$cloudOnly pruned=${plan.deleteIds.size}")
        Outcome.Done(synced, pending, cloudOnly, plan.deleteIds.size)
    }

    /** The media-read permission for this SDK level, including the API 34+ partial grant. */
    private fun hasMediaReadPermission(): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(appContext, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 ->
                granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
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

    private companion object {
        /** Minimum row count before the mass-prune cap (guard 4) can trigger. */
        const val MASS_PRUNE_MIN = 20
    }
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
    val relativePath: String? = null,
)

/** Cloud ground-truth item for [planReconcile] (a backup-folder file + its MD5). */
data class CloudTruth(
    val cloudId: String,
    val md5: String?,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val thumbnailUrl: String?,
    /** appProperties.sourcePath — restore target folder, carried into the row. */
    val sourcePath: String? = null,
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
    //
    //    Duplicate local copies of the same byte stream (same file saved twice,
    //    MediaStore double-registration): only ONE row can hold the cloudId
    //    (UNIQUE(provider, cloudId)), but EVERY copy whose bytes are in the cloud
    //    is SYNCED — "backed up" is a statement about bytes, not about which row
    //    owns the link. The unlinked copies carry cloudId=NULL ("link owned by a
    //    twin row"); they must never re-upload, and free-space must skip them
    //    (queries require cloudId IS NOT NULL).

    // Pass 0 — link preservation: a row already linked to a cloud file KEEPS it
    // while the file remains in the cloud listing and the local bytes are
    // unchanged (or unhashable). Scan order must never migrate a link to a twin.
    val preservedLinks = mutableMapOf<String, CloudTruth>()
    for (l in local) {
        val row = existingByUri[l.uri] ?: continue
        val linked = row.cloudId ?: continue
        val cloudFile = cloud.firstOrNull { it.cloudId == linked } ?: continue
        if (l.md5 == null || cloudFile.md5 == null || cloudFile.md5 == l.md5) {
            consumedCloudIds += linked
            preservedLinks[l.uri] = cloudFile
        }
    }
    for (l in local) {
        val row = existingByUri[l.uri]
        if (row != null && row.syncState == SyncState.PENDING_DELETE) {
            consumedRowIds += row.id
            continue
        }
        val match = preservedLinks[l.uri] ?: l.md5?.let { md5 ->
            cloudByMd5[md5]?.firstOrNull { it.cloudId !in consumedCloudIds }
        }
        if (match != null) consumedCloudIds += match.cloudId
        // Bytes provably in the cloud even when the match was consumed by a twin.
        val backedUpElsewhere = match == null && l.md5 != null && cloudByMd5[l.md5]?.isNotEmpty() == true
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
            relativePath = l.relativePath,
            syncState = if (match != null || backedUpElsewhere) SyncState.SYNCED else SyncState.PENDING_UPLOAD,
            excluded = row?.excluded ?: false,
            // Classify, never enqueue: queue membership survives reconcile untouched.
            queued = row?.queued ?: false,
            // Truth re-derived: a still-pending file gets FRESH upload chances
            // (its attempt counter may have been exhausted by an old transient).
            uploadAttempts = 0,
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
            // The cloud object carries the uploader's source folder (appProperties)
            // — restore targets it even though there is no local copy here.
            relativePath = c.sourcePath ?: row?.relativePath,
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
