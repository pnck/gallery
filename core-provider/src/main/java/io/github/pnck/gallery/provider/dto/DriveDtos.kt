package io.github.pnck.gallery.provider.dto

import com.squareup.moshi.JsonClass

// Google Drive REST API v3 DTOs (PRD §3.2). Retrofit + Moshi deserialization
// targets only — must never leak above the provider layer.

@JsonClass(generateAdapter = true)
data class DriveFileListResponse(
    val files: List<DriveFileDTO>,
    val nextPageToken: String?,
)

@JsonClass(generateAdapter = true)
data class DriveFileDTO(
    val id: String,
    val name: String? = null,
    val size: Long? = null,
    val md5Checksum: String? = null,
    /** Requires an Authorization: Bearer header to fetch (PRD §8.3). */
    val thumbnailLink: String? = null,
    val imageMediaMetadata: ImageMetadataDTO? = null,
)

@JsonClass(generateAdapter = true)
data class ImageMetadataDTO(
    val width: Int? = null,
    val height: Int? = null,
    /** EXIF capture time, e.g. "2026:07:06 12:00:00". */
    val time: String? = null,
)

@JsonClass(generateAdapter = true)
data class DriveStartPageTokenResponse(
    val startPageToken: String,
)

@JsonClass(generateAdapter = true)
data class DriveChangeListResponse(
    val changes: List<DriveChangeDTO>,
    val nextPageToken: String? = null,
    val newStartPageToken: String? = null,
)

@JsonClass(generateAdapter = true)
data class DriveChangeDTO(
    val fileId: String,
    val removed: Boolean = false,
    val file: DriveFileDTO? = null,
)
