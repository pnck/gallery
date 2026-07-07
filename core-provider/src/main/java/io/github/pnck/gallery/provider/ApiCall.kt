package io.github.pnck.gallery.provider

import io.github.pnck.gallery.network.ApiResult
import java.io.IOException
import retrofit2.Response

/**
 * Retrofit → ApiResult bridge (PRD §3.3). Classifies retryability:
 * 429 / 5xx / IOException are retryable (workers map them to Result.retry()).
 */
suspend fun <T, R> safeApiCall(
    call: suspend () -> Response<T>,
    transform: (T) -> R,
): ApiResult<R> = try {
    val response = call()
    val body = response.body()
    when {
        response.isSuccessful && body != null -> ApiResult.Success(transform(body))
        response.isSuccessful -> ApiResult.Error(response.code(), "Empty response body", retryable = false)
        else -> ApiResult.Error(
            code = response.code(),
            message = response.errorBody()?.string()?.take(500) ?: response.message(),
            retryable = response.code() == 429 || response.code() in 500..599,
        )
    }
} catch (e: IOException) {
    ApiResult.Error(code = -1, message = e.message ?: "Network I/O error", retryable = true)
} catch (e: Exception) {
    ApiResult.Error(code = -1, message = e.message ?: e.javaClass.simpleName, retryable = false)
}
