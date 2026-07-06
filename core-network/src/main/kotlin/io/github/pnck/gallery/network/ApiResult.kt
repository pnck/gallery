package io.github.pnck.gallery.network

/**
 * Standard response wrapper (PRD §3.3).
 *
 * Every provider method must return this; raw exceptions must never leak to the UI.
 */
sealed class ApiResult<out T> {
    data class Success<out T>(val data: T) : ApiResult<T>()

    data class Error(
        val code: Int,
        val message: String,
        /** True for 429 / transient IO errors — workers translate this into Result.retry(). */
        val retryable: Boolean,
    ) : ApiResult<Nothing>()
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Error -> this
}

inline fun <T> ApiResult<T>.onSuccess(block: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Success) block(data)
    return this
}

inline fun <T> ApiResult<T>.onError(block: (ApiResult.Error) -> Unit): ApiResult<T> {
    if (this is ApiResult.Error) block(this)
    return this
}
