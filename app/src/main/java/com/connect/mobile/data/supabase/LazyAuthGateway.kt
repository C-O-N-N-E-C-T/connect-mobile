package com.connect.mobile.data.supabase

/**
 * Creates the real gateway, and with it the SupabaseClient, on first use only (design 2, review R8).
 *
 * A cold start into the mission screen therefore builds no client: supabase-kt does not load the
 * stored session and does not retry token refreshes every few seconds during an offline deployment.
 * The client is built when a login, a logout or the start-up stale-session wipe needs it.
 */
class LazyAuthGateway(create: () -> SupabaseAuthGateway) : SupabaseAuthGateway {
    private val delegate by lazy(create)

    override suspend fun awaitInitialized() = delegate.awaitInitialized()
    override suspend fun signIn(email: String, password: String) = delegate.signIn(email, password)
    override suspend fun accessTokenOrNull() = delegate.accessTokenOrNull()
    override suspend fun currentUserIdOrNull() = delegate.currentUserIdOrNull()
    override suspend fun signOut() = delegate.signOut()
    override suspend fun clearLocalSession() = delegate.clearLocalSession()
    override suspend fun refreshSession() = delegate.refreshSession()
}
