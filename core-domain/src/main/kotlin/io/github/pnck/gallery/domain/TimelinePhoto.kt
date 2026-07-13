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
) {
    /** True when only the cloud copy remains (PRD §3.7); the grid keeps a cloud badge. */
    val showCloudIcon: Boolean get() = syncState == SyncState.CLOUD_ONLY
}
