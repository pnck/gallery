package io.github.pnck.gallery.provider.api

import io.github.pnck.gallery.provider.dto.DriveAboutResponse
import io.github.pnck.gallery.provider.dto.DriveChangeListResponse
import io.github.pnck.gallery.provider.dto.DriveFileDTO
import io.github.pnck.gallery.provider.dto.DriveFileListResponse
import io.github.pnck.gallery.provider.dto.DriveStartPageTokenResponse
import io.github.pnck.gallery.provider.dto.DriveUploadMetadata
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Google Drive v3 Retrofit surface (PRD §8.2, §4.2).
 * Base URL: https://www.googleapis.com/
 * Upload endpoints (multipart/resumable) are added with T-102.
 */
interface DriveApiService {

    @GET("drive/v3/files")
    suspend fun listFiles(
        // No image-only mime filter: the reconcile/downstream truth must include
        // EVERY file the app can see — an upload that ever went out with a
        // non-image MIME (an older build's fallback) would otherwise be invisible,
        // unmatchable by content hash, and re-uploaded forever. Folders excluded:
        // the backup folder itself must not become a CLOUD_ONLY photo row.
        @Query("q") query: String = "mimeType != 'application/vnd.google-apps.folder' and trashed = false",
        @Query("pageToken") pageToken: String?,
        @Query("pageSize") pageSize: Int = 100,
        @Query("fields") fields: String =
            "nextPageToken, files(id, name, mimeType, size, md5Checksum, createdTime, appProperties, thumbnailLink, imageMediaMetadata)",
    ): Response<DriveFileListResponse>

    /** Which Google account this token belongs to — diagnoses account mismatch. */
    @GET("drive/v3/about")
    suspend fun about(
        @Query("fields") fields: String = "user(emailAddress,displayName)",
    ): Response<DriveAboutResponse>

    /** Entry point of incremental sync — page tokens are NOT delta tokens (PRD §4.3). */
    @GET("drive/v3/changes/startPageToken")
    suspend fun getStartPageToken(): Response<DriveStartPageTokenResponse>

    @GET("drive/v3/changes")
    suspend fun listChanges(
        @Query("pageToken") pageToken: String,
        @Query("fields") fields: String =
            "nextPageToken, newStartPageToken, changes(fileId, removed, file(id, name, size, md5Checksum, createdTime, appProperties, thumbnailLink, imageMediaMetadata))",
    ): Response<DriveChangeListResponse>

    /** Create a file/folder from metadata only (used to make the app's own folder). */
    @POST("drive/v3/files")
    suspend fun createFile(
        @Body metadata: DriveUploadMetadata,
        @Query("fields") fields: String = "id, createdTime",
    ): Response<DriveFileDTO>

    @GET("drive/v3/files/{fileId}")
    suspend fun getFile(
        @Path("fileId") fileId: String,
        @Query("fields") fields: String =
            "id, name, size, md5Checksum, createdTime, appProperties, thumbnailLink, imageMediaMetadata",
    ): Response<DriveFileDTO>

    /**
     * Resumable upload, step 1 (PRD §4.4): register metadata, receive the session
     * URI in the Location response header. Works for any file size and survives
     * WorkManager's execution window (each attempt resumes the session).
     * X-Upload-Content-* declare the final size/type up front (recommended by the
     * protocol; lets Drive reject oversized/unknown-length uploads immediately).
     */
    @POST("upload/drive/v3/files?uploadType=resumable")
    suspend fun initResumableUpload(
        @Body metadata: DriveUploadMetadata,
        @Header("X-Upload-Content-Type") uploadContentType: String? = null,
        @Header("X-Upload-Content-Length") uploadContentLength: Long? = null,
        // Drive derives the FINAL upload response's fields from THIS initiation request,
        // so md5Checksum must be requested here (not just on the PUT) — otherwise the
        // upload response carries no hash and the SYNCED row never stores its identity,
        // defeating the anti-state-machine-failure MD5 dedup (phantom CLOUD_ONLY rows).
        @Query("fields") fields: String = "id, name, size, md5Checksum, appProperties, imageMediaMetadata",
    ): Response<Unit>

    /**
     * Resumable upload, step 2: PUT one chunk to the session URI with an explicit
     * `Content-Range: bytes first-last/total` (chunk sizes must be multiples of
     * 256 KiB except the final one). Drive answers 308 Resume Incomplete (with a
     * `Range: bytes=0-N` header) for accepted intermediate chunks and 200/201 for
     * the completed file — both are read by the caller, so this intentionally
     * bypasses safeApiCall's success-only view.
     *
     * Also used for the STATUS QUERY: Content-Range "bytes &#42;/&#42;" (asterisks)
     * with an empty body — 308+Range tells the confirmed offset, 200/201 means the
     * upload actually completed (its response was lost), 404/410 means the session died.
     */
    @PUT
    suspend fun uploadToSession(
        @Url sessionUri: String,
        @Header("Content-Range") contentRange: String,
        @Body body: RequestBody,
        @Query("fields") fields: String =
            "id, name, size, md5Checksum, thumbnailLink, imageMediaMetadata",
    ): Response<DriveFileDTO>

    /**
     * Browse a folder's direct children — ALL file types (My Drive feature). Needs the
     * drive.readonly grant to see files this app didn't create. Folders sort first.
     */
    @GET("drive/v3/files")
    suspend fun browseFolder(
        @Query("q") q: String,
        @Query("pageToken") pageToken: String?,
        @Query("orderBy") orderBy: String = "folder,name",
        @Query("pageSize") pageSize: Int = 200,
        @Query("fields") fields: String =
            "nextPageToken, files(id, name, mimeType, size, thumbnailLink)",
    ): Response<DriveFileListResponse>

    @Streaming
    @GET("drive/v3/files/{fileId}?alt=media")
    suspend fun downloadFile(
        @Path("fileId") fileId: String,
        /** e.g. "bytes=12345-" to resume a partial download (Drive honors Range). */
        @Header("Range") range: String? = null,
    ): Response<ResponseBody>

    @DELETE("drive/v3/files/{fileId}")
    suspend fun deleteFile(@Path("fileId") fileId: String): Response<Unit>
}
