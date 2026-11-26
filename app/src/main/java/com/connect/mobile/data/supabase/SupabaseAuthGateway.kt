package com.connect.mobile.data.supabase

import com.connect.mobile.auth.AuthError

/** The part of Supabase Auth the app uses; lets use cases be tested without a SupabaseClient. */
interface SupabaseAuthGateway {
    /** Waits (bounded) until a stored session has been loaded, so a later wipe is not undone. */
    suspend fun awaitInitialized()

    suspend fun signIn(email: String, password: String): SignInOutcome

    suspend fun accessTokenOrNull(): String?

    /** The Supabase user id (JWT `sub`) of the current session, or null. */
    suspend fun currentUserIdOrNull(): String?

    /** Remote Supabase /logout (scope LOCAL). May throw; never relied on for the local wipe. */
    suspend fun signOut()

    /** Clears the in-memory + persisted Supabase session and stops auto-refresh. */
    suspend fun clearLocalSession()

    /**
     * OSP-76 step 1: refreshes the stored session NOW (an explicit SDK refreshSession) and returns the
     * new access token. Called before every upload burst after connectivity returns, so an expired
     * token is never sent to POST /api/v1/sync. Never throws.
     */
    suspend fun refreshSession(): RefreshOutcome
}

sealed interface SignInOutcome {
    data object Success : SignInOutcome
    data class Failure(val error: AuthError) : SignInOutcome
}

/** OSP-76: result of [SupabaseAuthGateway.refreshSession]. */
sealed interface RefreshOutcome {
    /** A freshly issued access token. */
    data class Refreshed(val accessToken: String) : RefreshOutcome

    /** Network error, timeout or Auth server 5xx/429: keep the queue and retry with backoff (step 2). */
    data object Transient : RefreshOutcome

    /** No session, or the refresh token was refused: the rescuer must sign in online again (step 3). */
    data object Rejected : RefreshOutcome
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
