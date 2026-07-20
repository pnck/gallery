package io.github.pnck.gallery.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.pnck.gallery.domain.SyncState

/**
 * Photo row (PRD §3.4). Primary key is a locally generated UUID — file hashes are
 * deliberately NOT primary keys (full-file reads at scan time are unaffordable and
 * OneDrive has no MD5, PRD §3.6). Hashes are computed lazily at upload time.
 */
@Entity(
    tableName = "photos",
    indices = [
        Index(value = ["dateTaken"]),
        Index(value = ["provider", "cloudId"], unique = true),
        Index(value = ["localUri"]),
    ],
)
data class PhotoEntity(
    @PrimaryKey val id: String,
    /** content://media/... — presence means a local file exists. Never absolute paths (PRD §6.1). */
    val localUri: String?,
    val cloudId: String?,
    /** G_DRIVE / ONE_DRIVE. */
    val provider: String?,
    /** MD5 / QUICK_XOR / SHA1 — provider-specific, never compared cross-provider (PRD §3.5). */
    val contentHashType: String?,
    val contentHashValue: String?,
    /** Expires quickly; cache of the latest URL only, works with the auth interceptor (PRD §8.3). */
    val cloudThumbnailUrl: String?,
    /** Unix ms — the ONLY ordering key of the timeline. */
    val dateTaken: Long,
    /** MediaStore DATE_MODIFIED (sec); with [sizeBytes] it validates the cached MD5 —
     *  if either changed the file changed, so the stored hash is stale. */
    val dateModifiedSec: Long = 0,
    val width: Int,
    val height: Int,
    /** File size in bytes; 0 when unknown (e.g. a cloud row before metadata is known). */
    val sizeBytes: Long = 0,
    /** MediaStore BUCKET_ID of the local folder (null for cloud-only photos). */
    val bucketId: String? = null,
    /** MediaStore BUCKET_DISPLAY_NAME — the folder label shown in the directory filter. */
    val bucketName: String? = null,
    val syncState: SyncState,
    /**
     * User opted this photo out of automatic backup ("clear queue"): it stays local
     * and visible but the bulk sweep skips it. Orthogonal to [syncState] so the
     * four-state machine (invariant #2) is untouched. Selecting it for an explicit
     * sync clears this.
     */
    val excluded: Boolean = false,
    /** Consecutive upload batch attempts that included this file and failed — a
     *  poisoned file is skipped after the cap so it can't head-of-line-block the
     *  queue. Reset on success, on reconcile, and on explicit user sync. */
    val uploadAttempts: Int = 0,
    /** Wall ms of the last batch attempt (per-file backoff, diagnostics). */
    val lastUploadAttemptAt: Long = 0,
)

/** RemoteMediator cursors (PRD §3.4): initial paging tokens + delta tokens per target. */
@Entity(tableName = "sync_keys")
data class SyncKeyEntity(
    /** "drive_timeline" / "onedrive_timeline". */
    @PrimaryKey val target: String,
    val nextPageToken: String?,
    /** Drive startPageToken / Graph deltaLink. */
    val deltaToken: String?,
)

/**
 * A persisted Drive resumable-upload session (PRD §4.4). Written at session init,
 * [bytesConfirmed] advanced after every chunk ack, deleted on completion — so an
 * interrupted upload (killed process, dead tunnel, reboot) resumes at the
 * server-confirmed offset instead of restarting from byte 0.
 */
@Entity(tableName = "upload_sessions")
data class UploadSessionEntity(
    @PrimaryKey val photoId: String,
    val sessionUri: String,
    /** Bytes the SERVER has confirmed — the next offset to send. */
    val bytesConfirmed: Long,
    val totalBytes: Long,
    val mimeType: String,
    val updatedAtEpochMs: Long,
)
