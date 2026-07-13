package io.github.pnck.gallery.domain

/**
 * A device media folder (MediaStore bucket, PRD §6.1) offered for the scan-allowlist
 * / directory filter. [id] is the stable MediaStore BUCKET_ID; [name] the display
 * name (e.g. "Camera", "Screenshots"); [count] how many images it holds.
 */
data class MediaBucket(
    val id: String,
    val name: String,
    val count: Int,
)
