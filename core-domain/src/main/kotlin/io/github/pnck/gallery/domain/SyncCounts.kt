package io.github.pnck.gallery.domain

/**
 * Per-state totals for the sync-status panel (PRD §9.1).
 *
 * Counts follow the DERIVED badge semantics (see SyncBadge), never raw syncState —
 * otherwise every freshly scanned row (PENDING_UPLOAD) would show up as "waiting
 * to back up" when the queue is in fact built manually:
 *
 *  [queued]        QUEUED — manually included, waiting for the next sweep
 *  [pendingUpload] LOCAL_ONLY/UNKNOWN — local, not backed up, NOT queued (won't sync)
 *  [synced]        BACKED_UP
 *  [cloudOnly]     CLOUD_ONLY
 *  [pendingDelete] tombstones awaiting cloud deletion
 */
data class SyncCounts(
    val pendingUpload: Int,
    val queued: Int,
    val synced: Int,
    val cloudOnly: Int,
    val pendingDelete: Int,
)
