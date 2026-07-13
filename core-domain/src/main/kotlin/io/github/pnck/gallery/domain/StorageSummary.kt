package io.github.pnck.gallery.domain

/**
 * Aggregate footprint of the app's library on this device, for the space-management
 * screen (T-302, PRD §7.3). Device-level free/total bytes are read separately by the
 * UI (StatFs) since they are not a Room concern.
 *
 * All byte figures come from the persisted per-photo [sizeBytes]; they are exact for
 * local originals and best-effort for cloud metadata.
 */
data class StorageSummary(
    /** Bytes of every photo that still has a local copy on this device. */
    val localBytes: Long,
    /** Bytes of already-backed-up photos whose local copy can be safely released. */
    val freeableBytes: Long,
    /** Bytes of local photos not yet backed up (freeing these would lose them). */
    val notBackedUpBytes: Long,
    /** How many photos are freeable (backed up + still local). */
    val freeableCount: Int,
    /** How many photos have a local copy on this device. */
    val localCount: Int,
)
