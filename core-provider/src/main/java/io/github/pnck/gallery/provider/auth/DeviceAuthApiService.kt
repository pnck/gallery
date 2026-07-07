package io.github.pnck.gallery.provider.auth

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * OAuth device-flow endpoints (ADR-0001). Base URL is irrelevant — every call
 * uses an absolute @Url so the same service works for Google and Microsoft.
 */
interface DeviceAuthApiService {

    @FormUrlEncoded
    @POST
    suspend fun requestDeviceCode(
        @Url endpoint: String,
        @Field("client_id") clientId: String,
        @Field("scope") scope: String,
    ): Response<DeviceCodeResponse>

    @FormUrlEncoded
    @POST
    suspend fun exchangeDeviceCode(
        @Url endpoint: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("device_code") deviceCode: String,
        @Field("grant_type") grantType: String = "urn:ietf:params:oauth:grant-type:device_code",
    ): Response<TokenResponse>

    @FormUrlEncoded
    @POST
    suspend fun refreshToken(
        @Url endpoint: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("refresh_token") refreshToken: String,
        @Field("grant_type") grantType: String = "refresh_token",
    ): Response<TokenResponse>
}

@JsonClass(generateAdapter = true)
data class DeviceCodeResponse(
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "user_code") val userCode: String,
    // Google returns verification_url; the RFC/Microsoft use verification_uri.
    @Json(name = "verification_url") val verificationUrl: String? = null,
    @Json(name = "verification_uri") val verificationUri: String? = null,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "interval") val interval: Int = 5,
)

@JsonClass(generateAdapter = true)
data class TokenResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "expires_in") val expiresIn: Int? = null,
    @Json(name = "token_type") val tokenType: String? = null,
    // Present while pending / on failure: authorization_pending, slow_down,
    // access_denied, expired_token.
    @Json(name = "error") val error: String? = null,
    @Json(name = "error_description") val errorDescription: String? = null,
)
