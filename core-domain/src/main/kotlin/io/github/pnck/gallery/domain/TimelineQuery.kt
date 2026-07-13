package io.github.pnck.gallery.domain

/**
 * How the timeline grid is ordered and filtered (PRD §9.1). This is a pure view
 * concern — the four-state machine (invariant #2) is never touched by it.
 *
 * [bucketIds] doubles as the *scan allowlist*: an empty set means "all folders",
 * a non-empty set means the library is restricted to those device folders both in
 * the grid AND in what the scanner imports/backs up. Cloud-only photos (which have
 * no local folder) are always shown regardless of the allowlist.
 */
data class TimelineQuery(
    val sort: TimelineSort = TimelineSort.DATE_DESC,
    val filter: SyncFilter = SyncFilter.ALL,
    val bucketIds: Set<String> = emptySet(),
)

/** Ordering options surfaced in the timeline's view-options sheet. */
enum class TimelineSort {
    /** Newest first (default) — orders by dateTaken DESC. */
    DATE_DESC,

    /** Oldest first. */
    DATE_ASC,

    /** Largest file first. */
    SIZE_DESC,

    /** Smallest file first. */
    SIZE_ASC,
}

/** Which sync states the timeline shows. Maps onto the four-state machine codes. */
enum class SyncFilter {
    /** Everything (default). */
    ALL,

    /** Local originals still waiting for backup (PENDING_UPLOAD, not excluded). */
    NOT_BACKED_UP,

    /** Photos that have a cloud copy (SYNCED). */
    BACKED_UP,

    /** Cloud-only photos whose local copy was released (CLOUD_ONLY). */
    CLOUD_ONLY,
}
