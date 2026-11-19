package com.connect.mobile.data.backend

/** connect-backend auth endpoints (Jira OSP-60). */
interface AuthBackend {
    suspend fun bindSession(accessToken: String, deviceId: String, deviceName: String): BackendResult<SessionResponse>

    suspend fun logout(accessToken: String): BackendResult<Unit>
}

sealed interface BackendResult<out T> {
    data class Ok<T>(val value: T) : BackendResult<T>
    data class Err(val error: BackendError) : BackendResult<Nothing>
}

sealed interface BackendError {
    data class Http(val status: Int, val code: String?, val message: String?) : BackendError
    data object Network : BackendError
    data object Malformed : BackendError
}
