package com.connect.mobile.auth

/** Every failure the Field Rescuer login can show (design section 3.6). */
sealed interface AuthError {
    data object InvalidInput : AuthError
    data object BadCredentials : AuthError
    data object AccountDisabled : AuthError
    data object AccountNotReady : AuthError
    data object RateLimited : AuthError
    data object Network : AuthError
    data object RoleNotAllowed : AuthError
    data object SessionRejected : AuthError
    data object DeviceRevoked : AuthError
    data object RequestRejected : AuthError
    data object GrantInvalid : AuthError
    data object Server : AuthError
    data object NotConfigured : AuthError

    companion object {
        val all: List<AuthError> = listOf(
            InvalidInput, BadCredentials, AccountDisabled, AccountNotReady, RateLimited, Network,
            RoleNotAllowed, SessionRejected, DeviceRevoked, RequestRejected, GrantInvalid, Server,
            NotConfigured,
        )
    }
}
