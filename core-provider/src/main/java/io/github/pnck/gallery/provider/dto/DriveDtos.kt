package io.github.pnck.gallery.provider.dto

import com.squareup.moshi.JsonClass

// Google Drive REST API v3 DTOs (PRD §3.2). Retrofit + Moshi deserialization
// targets only — must never leak above the provider layer.

@JsonClass(generateAdapter = true)
data class DriveFileListResponse(
    val files: List<DriveFileDTO>,
    val nextPageToken: String?,
)

/** drive/v3/about — used to show which account the app is actually signed into. */
@JsonClass(generateAdapter = true)
data class DriveAboutResponse(val user: DriveUser?)

@JsonClass(generateAdapter = true)
data class DriveUser(
    val emailAddress: String? = null,
    val displayName: String? = null,
)

@JsonClass(generateAdapter = true)
data class DriveFileDTO(
    val id: String,
    val name: String? = null,
    /** e.g. "image/jpeg", "application/pdf", "application/vnd.google-apps.folder". */
    val mimeType: String? = null,
    val size: Long? = null,
    val md5Checksum: String? = null,
    /** RFC 3339 creation time — sorts chronologically as a raw string. */
    val createdTime: String? = null,
    /** App-private key/values written at upload (source folder etc., PRD §3.5). */
    val appProperties: Map<String, String>? = null,
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

/** Request body for the resumable-upload initiation call (and folder creation). */
@JsonClass(generateAdapter = true)
data class DriveUploadMetadata(
    val name: String,
    val mimeType: String? = null,
    /** Parent folder ids — pins uploads to the app's own "BYOS Gallery" folder. */
    val parents: List<String>? = null,
    /** App-private provenance (sourcePath, mediaStoreId) — readable only by this
     *  OAuth client, so a later restore can return the file to its source folder. */
    val appProperties: Map<String, String>? = null,
)
