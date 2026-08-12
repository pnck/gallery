package io.github.pnck.gallery.domain

/**
 * A device media folder (MediaStore bucket, PRD §6.1) offered for the scan-allowlist
 * / directory filter. [id] is the stable MediaStore BUCKET_ID; [name] the display
 * name (e.g. "Camera", "Screenshots").
 */
data class MediaBucket(
    val id: String,
    val name: String,
    /** Human-readable folder path (e.g. "DCIM/Camera/"), so same-named folders under
     *  different paths can be told apart. Null when the platform can't provide it. */
    val path: String?,
    /** Total items (photos + videos) — used for the "biggest first" sort. */
    val count: Int,
    /** How many of [count] are videos; the rest are photos. */
    val videoCount: Int = 0,
) {
    val photoCount: Int get() = count - videoCount
}
