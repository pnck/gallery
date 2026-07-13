package io.github.pnck.gallery.domain

/**
 * A device media folder (MediaStore bucket, PRD §6.1) offered for the scan-allowlist
 * / directory filter. [id] is the stable MediaStore BUCKET_ID; [name] the display
 * name (e.g. "Camera", "Screenshots"); [count] how many images it holds.
 */
data class MediaBucket(
    val id: String,
    val name: String,
    /** Human-readable folder path (e.g. "DCIM/Camera/"), so same-named folders under
     *  different paths can be told apart. Null when the platform can't provide it. */
    val path: String?,
    val count: Int,
)
