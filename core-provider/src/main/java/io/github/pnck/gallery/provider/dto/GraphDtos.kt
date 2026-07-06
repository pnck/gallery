package io.github.pnck.gallery.provider.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// Microsoft Graph (OneDrive) DTOs (PRD §3.2). Deserialization targets only.

@JsonClass(generateAdapter = true)
data class GraphChildrenResponse(
    @Json(name = "value") val items: List<GraphItemDTO>,
    @Json(name = "@odata.nextLink") val nextLink: String? = null,
    /** Incremental sync cursor — present on the final page of a delta query. */
    @Json(name = "@odata.deltaLink") val deltaLink: String? = null,
)

@JsonClass(generateAdapter = true)
data class GraphItemDTO(
    val id: String,
    val name: String? = null,
    val size: Long? = null,
    val file: GraphFileFacet? = null,
    val image: GraphImageFacet? = null,
    val photo: GraphPhotoFacet? = null,
    /** Set on delta responses when the item was deleted server-side. */
    val deleted: GraphDeletedFacet? = null,
)

@JsonClass(generateAdapter = true)
data class GraphFileFacet(
    val mimeType: String? = null,
    val hashes: GraphHashesFacet? = null,
)

/** OneDrive has NO md5 (PRD §3.5): personal = quickXor + sha1, business = quickXor only. */
@JsonClass(generateAdapter = true)
data class GraphHashesFacet(
    @Json(name = "quickXorHash") val quickXorHash: String? = null,
    @Json(name = "sha1Hash") val sha1Hash: String? = null,
)

@JsonClass(generateAdapter = true)
data class GraphImageFacet(
    val width: Int? = null,
    val height: Int? = null,
)

@JsonClass(generateAdapter = true)
data class GraphPhotoFacet(
    /** ISO 8601, e.g. "2026-07-06T12:00:00Z". */
    val takenDateTime: String? = null,
)

@JsonClass(generateAdapter = true)
class GraphDeletedFacet(
    val state: String? = null,
)
