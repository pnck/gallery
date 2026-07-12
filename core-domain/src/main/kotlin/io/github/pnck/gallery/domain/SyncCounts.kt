package io.github.pnck.gallery.domain

/** Per-state totals for the sync-status panel (PRD §9.1). */
data class SyncCounts(
    val pendingUpload: Int,
    val synced: Int,
    val cloudOnly: Int,
    val pendingDelete: Int,
)
