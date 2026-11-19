package com.connect.mobile.data.supabase

import com.connect.mobile.auth.AuthError

/** The part of Supabase Auth the app uses; lets use cases be tested without a SupabaseClient. */
interface SupabaseAuthGateway {
    /** Waits (bounded) until a stored session has been loaded, so a later wipe is not undone. */
    suspend fun awaitInitialized()

    suspend fun signIn(email: String, password: String): SignInOutcome

    suspend fun accessTokenOrNull(): String?

    /** Remote Supabase /logout (scope LOCAL). May throw; never relied on for the local wipe. */
    suspend fun signOut()

    /** Clears the in-memory + persisted Supabase session and stops auto-refresh. */
    suspend fun clearLocalSession()
}

sealed interface SignInOutcome {
    data object Success : SignInOutcome
    data class Failure(val error: AuthError) : SignInOutcome
}

/** Pure mapping of Supabase Auth failures (design section 3.6). */
object SupabaseErrorMapper {
    fun fromRest(errorCode: String?, httpStatus: Int): AuthError = when {
        errorCode == "invalid_credentials" || errorCode == "validation_failed" -> AuthError.BadCredentials
        errorCode == "user_banned" -> AuthError.AccountDisabled
        errorCode == "email_not_confirmed" -> AuthError.AccountNotReady
        errorCode == "over_request_rate_limit" || httpStatus == 429 -> AuthError.RateLimited
        httpStatus == 400 -> AuthError.BadCredentials
        else -> AuthError.Server
    }
}
