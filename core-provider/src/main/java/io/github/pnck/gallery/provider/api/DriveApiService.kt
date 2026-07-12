package io.github.pnck.gallery.provider.api

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
        @Query("q") query: String = "mimeType contains 'image/' and trashed = false",
        @Query("pageToken") pageToken: String?,
        @Query("pageSize") pageSize: Int = 100,
        @Query("fields") fields: String =
            "nextPageToken, files(id, name, size, md5Checksum, thumbnailLink, imageMediaMetadata)",
    ): Response<DriveFileListResponse>

    /** Entry point of incremental sync — page tokens are NOT delta tokens (PRD §4.3). */
    @GET("drive/v3/changes/startPageToken")
    suspend fun getStartPageToken(): Response<DriveStartPageTokenResponse>

    @GET("drive/v3/changes")
    suspend fun listChanges(
        @Query("pageToken") pageToken: String,
        @Query("fields") fields: String =
            "nextPageToken, newStartPageToken, changes(fileId, removed, file(id, name, size, md5Checksum, thumbnailLink, imageMediaMetadata))",
    ): Response<DriveChangeListResponse>

    /** Create a file/folder from metadata only (used to make the app's own folder). */
    @POST("drive/v3/files")
    suspend fun createFile(
        @Body metadata: DriveUploadMetadata,
        @Query("fields") fields: String = "id",
    ): Response<DriveFileDTO>

    @GET("drive/v3/files/{fileId}")
    suspend fun getFile(
        @Path("fileId") fileId: String,
        @Query("fields") fields: String =
            "id, name, size, md5Checksum, thumbnailLink, imageMediaMetadata",
    ): Response<DriveFileDTO>

    /**
     * Resumable upload, step 1 (PRD §4.4): register metadata, receive the session
     * URI in the Location response header. Works for any file size and survives
     * WorkManager's execution window (each attempt resumes the session).
     */
    @POST("upload/drive/v3/files?uploadType=resumable")
    suspend fun initResumableUpload(
        @Body metadata: DriveUploadMetadata,
        @Query("fields") fields: String = "id",
    ): Response<Unit>

    /** Resumable upload, step 2: PUT the bytes to the session URI. */
    @PUT
    suspend fun uploadToSession(
        @Url sessionUri: String,
        @Body body: RequestBody,
        @Query("fields") fields: String =
            "id, name, size, md5Checksum, thumbnailLink, imageMediaMetadata",
    ): Response<DriveFileDTO>

    @Streaming
    @GET("drive/v3/files/{fileId}?alt=media")
    suspend fun downloadFile(@Path("fileId") fileId: String): Response<ResponseBody>

    @DELETE("drive/v3/files/{fileId}")
    suspend fun deleteFile(@Path("fileId") fileId: String): Response<Unit>
}
