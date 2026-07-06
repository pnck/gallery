package io.github.pnck.gallery.domain

/**
 * UI domain model — the anti-corruption layer (PRD §3.8).
 *
 * The UI must only ever see this type; DTOs and Room entities are forbidden above
 * the repository. Keep it immutable so Compose treats it as stable (PRD §2.4).
 */
data class TimelinePhoto(
    val id: String,
    /** Computed by the repository: localUri if present, otherwise "provider://{cloudId}". */
    val renderUri: String,
    /** width / height, used by grid/staggered layouts. */
    val aspectRatio: Float,
    /** True when syncState == CLOUD_ONLY; the grid draws a cloud badge. */
    val showCloudIcon: Boolean,
)
