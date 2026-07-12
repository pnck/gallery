package io.github.pnck.gallery.data.sync

import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.data.db.PhotoEntity
import io.github.pnck.gallery.data.db.SyncKeyDao
import io.github.pnck.gallery.data.db.SyncKeyEntity
import io.github.pnck.gallery.domain.SyncState
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.CloudFile
import io.github.pnck.gallery.provider.ContentHash
import io.github.pnck.gallery.provider.ICloudStorageProvider
import io.github.pnck.gallery.provider.ProviderType
import java.util.UUID

/**
 * Cloud → Room downstream sync (T-303/T-402, PRD §4.3). This is what makes the
 * gallery a *shared* library: photos uploaded from other devices land here as
 * CLOUD_ONLY rows, and server-side deletions are reflected locally.
 *
 * First run: a full listPhotos sweep seeds CLOUD_ONLY rows, then a change cursor
 * is captured. Later runs use the Drive Changes API (delta), never page tokens
 * (invariant #4). Rows are matched by (provider, cloudId): a file we already
 * track — including one we uploaded ourselves (markAsSynced set its cloudId) — is
 * left untouched, so SYNCED never gets clobbered back to CLOUD_ONLY.
 */
class DownstreamSyncProcessor(
    private val provider: ICloudStorageProvider,
    private val photoDao: PhotoDao,
    private val syncKeyDao: SyncKeyDao,
) {
    sealed interface Outcome {
        data class Done(val inserted: Int, val removed: Int) : Outcome
        data object Retry : Outcome
    }

    private val target: String = "${provider.providerType.name.lowercase()}_timeline"

    suspend fun sync(): Outcome {
        val key = syncKeyDao.get(target)
        return if (key?.deltaToken == null) initialFullSync() else deltaSync(key.deltaToken)
    }

    private suspend fun initialFullSync(): Outcome {
        val known = photoDao.getKnownCloudIds(provider.providerType.name).toMutableSet()
        var inserted = 0
        var pageToken: String? = null
        do {
            val page = when (val res = provider.listPhotos(pageToken)) {
                is ApiResult.Success -> res.data
                is ApiResult.Error -> return if (res.retryable) Outcome.Retry else break
            }
            inserted += ingest(page.files, known)
            pageToken = page.nextPageToken
        } while (pageToken != null)

        // Establish the delta cursor for subsequent incremental runs.
        val cursor = when (val res = provider.fetchChanges(null)) {
            is ApiResult.Success -> res.data.newDeltaToken
            is ApiResult.Error -> return if (res.retryable) Outcome.Retry else Outcome.Done(inserted, 0)
        }
        syncKeyDao.upsert(SyncKeyEntity(target, nextPageToken = null, deltaToken = cursor))
        return Outcome.Done(inserted, 0)
    }

    private suspend fun deltaSync(deltaToken: String): Outcome {
        val changes = when (val res = provider.fetchChanges(deltaToken)) {
            is ApiResult.Success -> res.data
            is ApiResult.Error -> return Outcome.Retry
        }
        val known = photoDao.getKnownCloudIds(provider.providerType.name).toMutableSet()
        val inserted = ingest(changes.upserted, known)
        // MVP delete policy (PRD §4.3): drop the row for a server-side deletion.
        if (changes.deletedCloudIds.isNotEmpty()) photoDao.deleteByCloudIds(changes.deletedCloudIds)
        syncKeyDao.upsert(SyncKeyEntity(target, nextPageToken = null, deltaToken = changes.newDeltaToken))
        return Outcome.Done(inserted, changes.deletedCloudIds.size)
    }

    /**
     * Insert new cloud files as CLOUD_ONLY, but first try to recognise an existing
     * row by content hash (PRD §3.5). If a row already holds this photo's bytes but
     * lost its cloud link (a broken state machine), re-link it instead of creating a
     * duplicate. [known] is the running set of already-tracked cloud ids.
     * @return number of genuinely new rows inserted.
     */
    private suspend fun ingest(files: List<CloudFile>, known: MutableSet<String>): Int {
        val providerName = provider.providerType.name
        var inserted = 0
        for (file in files) {
            if (file.id in known) continue
            val md5 = (file.contentHash as? ContentHash.Md5)?.value
            if (md5 != null) {
                val existing = photoDao.findByContentHash(providerName, "MD5", md5)
                if (existing != null) {
                    if (existing.cloudId == null) {
                        val state = if (existing.localUri != null) SyncState.SYNCED else SyncState.CLOUD_ONLY
                        photoDao.linkCloud(existing.id, file.id, providerName, state.code)
                    }
                    known += file.id
                    continue
                }
            }
            photoDao.upsertAll(listOf(file.toCloudOnlyEntity()))
            known += file.id
            inserted++
        }
        return inserted
    }

    private fun CloudFile.toCloudOnlyEntity(): PhotoEntity {
        val (hashType, hashValue) = contentHash.typeAndValue()
        return PhotoEntity(
            id = UUID.randomUUID().toString(),
            localUri = null,
            cloudId = id,
            provider = this.provider.name,
            contentHashType = hashType,
            contentHashValue = hashValue,
            cloudThumbnailUrl = thumbnailUrl,
            dateTaken = creationTime,
            width = width,
            height = height,
            syncState = SyncState.CLOUD_ONLY,
        )
    }

    private fun ContentHash.typeAndValue(): Pair<String?, String?> = when (this) {
        is ContentHash.Md5 -> "MD5" to value
        is ContentHash.QuickXor -> "QUICK_XOR" to value
        is ContentHash.Sha1 -> "SHA1" to value
        ContentHash.None -> null to null
    }
}
