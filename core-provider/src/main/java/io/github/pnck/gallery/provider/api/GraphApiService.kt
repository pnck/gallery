package io.github.pnck.gallery.provider.api

import io.github.pnck.gallery.provider.dto.GraphChildrenResponse
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Url

/**
 * Microsoft Graph v1.0 Retrofit surface (PRD §4.2).
 * Base URL: https://graph.microsoft.com/v1.0/
 * Upload session endpoints are added with T-103.
 */
interface GraphApiService {

    @GET("me/drive/special/photos/children")
    suspend fun listPhotosChildren(): Response<GraphChildrenResponse>

    /** Follow @odata.nextLink / resume a delta with @odata.deltaLink (absolute URLs). */
    @GET
    suspend fun continueListing(@Url absoluteUrl: String): Response<GraphChildrenResponse>

    /** Initial delta query; final page carries @odata.deltaLink (PRD §4.3). */
    @GET("me/drive/root/delta")
    suspend fun startDelta(): Response<GraphChildrenResponse>

    @DELETE("me/drive/items/{itemId}")
    suspend fun deleteItem(@Path("itemId") itemId: String): Response<Unit>
}
