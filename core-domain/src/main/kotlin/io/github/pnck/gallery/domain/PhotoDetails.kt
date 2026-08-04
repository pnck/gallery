package io.github.pnck.gallery.domain

/**
 * Row-level facts for the "photo details" panel (PRD §9.1, T-403). EXIF (camera,
 * exposure, GPS) is read separately from the local file by the UI layer; this is
 * the DB-known metadata, available even for CLOUD_ONLY photos.
 */
data class PhotoDetails(
    val id: String,
    val width: Int,
    val height: Int,
    val dateTakenMs: Long,
    val syncState: SyncState,
    val localUri: String?,
    val cloudId: String?,
    val provider: String?,
    val contentHashType: String?,
    val contentHashValue: String?,
    /** MediaStore bucket (folder) display name of the local copy, when known. */
    val bucketName: String? = null,
)
