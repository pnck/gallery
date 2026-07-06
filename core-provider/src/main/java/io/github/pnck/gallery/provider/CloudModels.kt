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
)

data class CloudPage(
    val files: List<CloudFile>,
    val nextPageToken: String?,
)

/** Result of a delta/changes query (PRD §4.3). */
data class CloudChangeSet(
    val upserted: List<CloudFile>,
    val deletedCloudIds: List<String>,
    val newDeltaToken: String,
)
