package io.github.pnck.gallery.domain

/**
 * UI domain model — the anti-corruption layer (PRD §3.8).
 *
 * The UI must only ever see this type; DTOs and Room entities are forbidden above
 * the repository. Keep it immutable so Compose treats it as stable (PRD §2.4).
 */
data class TimelinePhoto(
    val id: String,
    /** Computed by the repository: localUri if present, otherwise "{provider}://{cloudId}". */
    val renderUri: String,
    /** width / height, used by grid/staggered layouts. */
    val aspectRatio: Float,
    /** Unix ms the photo was taken — the timeline's ordering key and the fast-scroll label. */
    val dateTaken: Long,
    /** File size in bytes (0 when unknown), for size sorting and the space-management view. */
    val sizeBytes: Long,
    /** The four-state machine value (PRD §3.7); the grid draws a per-state badge. */
    val syncState: SyncState,
    /** content://media/... when a local copy exists — used by share/edit intents. */
    val localUri: String?,
    /** Drive/Graph file id when a cloud copy exists — used by download/original view. */
    val cloudId: String?,
    /** G_DRIVE / ONE_DRIVE, or null before the first upload. */
    val provider: String?,
    /** User dropped this photo from automatic backup ("clear queue"); still local. */
    val excluded: Boolean = false,
    /**
     * True once the photo's bytes have been compared against the cloud (content
     * hash computed by reconcile or at upload). A freshly scanned PENDING_UPLOAD
     * row is UNCLASSIFIED — it may well be backed up already; the grid shows the
     * UNKNOWN badge for it rather than a premature "not backed up".
     */
    val classified: Boolean = true,
    /**
     * In the sync queue (see PhotoEntity.queued): sync is never automatic — a
     * classified-but-unqueued photo is LOCAL_ONLY ("not backed up", the default);
     * it becomes QUEUED only via an explicit include ("Back up now" / selection).
     */
    val queued: Boolean = true,
    /** Batch attempts so far; > 0 marks the photo as actively uploading/retrying. */
    val uploadAttempts: Int = 0,
) {
    /** True when only the cloud copy remains (PRD §3.7); the grid keeps a cloud badge. */
    val showCloudIcon: Boolean get() = syncState == SyncState.CLOUD_ONLY
}

/**
 * The presentation badge, DERIVED from the row — never persisted, so it can never
 * disagree with the data (PRD §3.7 keeps its four persisted sync states; these are
 * projections, not new states):
 *
 *  UNKNOWN     pending + never hashed (fresh scan; reconcile hasn't classified it)
 *  LOCAL_ONLY  classified, not backed up, NOT queued (the default — sync is manual)
 *  QUEUED      manually included, waiting for the next sweep
 *  UPLOADING   queued and claimed by a batch (attempts > 0)
 *  BACKED_UP   synced
 *  CLOUD_ONLY  cloud copy only
 *  EXCLUDED    user opted out of backup entirely
 */
enum class SyncBadge { UNKNOWN, LOCAL_ONLY, QUEUED, UPLOADING, BACKED_UP, CLOUD_ONLY, EXCLUDED, NONE }

val TimelinePhoto.badge: SyncBadge
    get() = when {
        syncState == SyncState.SYNCED -> SyncBadge.BACKED_UP
        syncState == SyncState.CLOUD_ONLY -> SyncBadge.CLOUD_ONLY
        syncState == SyncState.PENDING_DELETE -> SyncBadge.NONE
        excluded -> SyncBadge.EXCLUDED
        !classified -> SyncBadge.UNKNOWN
        !queued -> SyncBadge.LOCAL_ONLY
        uploadAttempts > 0 -> SyncBadge.UPLOADING
        else -> SyncBadge.QUEUED
    }
