package io.github.pnck.gallery.provider.api

import io.github.pnck.gallery.provider.dto.DriveChangeListResponse
import io.github.pnck.gallery.provider.dto.DriveFileListResponse
import io.github.pnck.gallery.provider.dto.DriveStartPageTokenResponse
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

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

    @DELETE("drive/v3/files/{fileId}")
    suspend fun deleteFile(@Path("fileId") fileId: String): Response<Unit>
}
