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
    val width: Int,
    val height: Int,
    val syncState: SyncState,
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
