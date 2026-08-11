package io.github.pnck.gallery.provider

/** Storage backends supported by the virtual backend (PRD §3.1). */
enum class ProviderType { G_DRIVE, ONE_DRIVE }

/**
 * Content checksums are provider-specific and MUST NOT be compared across
 * providers (PRD §3.5): Drive returns MD5, OneDrive returns quickXor/sha1.
 * Dedup ("秒传") is only valid within a single provider.
 */
sealed interface ContentHash {
    /** Google Drive. */
    data class Md5(val value: String) : ContentHash

    /** OneDrive (personal + business). */
    data class QuickXor(val value: String) : ContentHash

    /** OneDrive personal fallback. */
    data class Sha1(val value: String) : ContentHash

    data object None : ContentHash
}

/**
 * Unified cloud object — every provider must normalize its DTOs into this
 * before anything leaves the provider layer (PRD §3.1).
 */
data class CloudFile(
    /** Cloud primary key (Drive fileId / Graph itemId). */
    val id: String,
    val provider: ProviderType,
    val contentHash: ContentHash,
    val sizeBytes: Long,
    /** Unix timestamp (ms), sourced from EXIF / photo facet. */
    val creationTime: Long,
    val width: Int,
    val height: Int,
    /** Short-lived; for immediate rendering only — never persist long-term (PRD §8.3). */
    val thumbnailUrl: String?,
    /** Original display name — restore uses it instead of a synthetic one. */
    val name: String? = null,
    /** appProperties.sourcePath: the device folder the photo was uploaded from
     *  (e.g. "DCIM/Camera/") — the restore target on any device. */
    val sourcePath: String? = null,
    /** Video mime — film badge + player. */
    val isVideo: Boolean = false,
    /** videoMediaMetadata.durationMillis when the provider knows it; 0 otherwise. */
    val durationMs: Long = 0,
)

data class CloudPage(
    val files: List<CloudFile>,
    val nextPageToken: String?,
)

/**
 * A generic entry in the "My Drive" browser (any file type, incl. folders). This is
 * the separate broad-read feature — distinct from [CloudFile], which is the backup
 * layer's photo model. Requires drive.readonly.
 */
data class DriveEntry(
    val id: String,
    val name: String,
    val mimeType: String,
    /** Null for folders / when Drive omits it. */
    val sizeBytes: Long?,
    /** Short-lived thumbnail URL (needs a Bearer header); null for non-previewable types. */
    val thumbnailUrl: String?,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME
    val isImage: Boolean get() = mimeType.startsWith("image/")

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        /** Drive's virtual root folder id (the "My Drive" top level). */
        const val ROOT_ID = "root"
    }
}

data class DriveListing(
    val entries: List<DriveEntry>,
    val nextPageToken: String?,
)

/** Result of a delta/changes query (PRD §4.3). */
data class CloudChangeSet(
    val upserted: List<CloudFile>,
    val deletedCloudIds: List<String>,
    val newDeltaToken: String,
)
