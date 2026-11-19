package com.connect.mobile.auth

import com.connect.mobile.data.backend.AuthBackend
import com.connect.mobile.data.session.SessionStore
import com.connect.mobile.data.supabase.SupabaseAuthGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Teardown shared by "End Mission / Log Out", role rejection, every post-sign-in failure and the
 * stale-session start-up rule (design 4.2).
 *
 * Deletes ONLY the local grant and tokens. It has no Context and no DB handle, so it cannot reach
 * unsynced victim records; the install device_id is kept.
 */
class LogoutUseCase(
    private val gateway: SupabaseAuthGateway,
    private val backend: AuthBackend,
    private val store: SessionStore,
    private val remoteTimeoutMs: Long = 5_000,
) {
    suspend operator fun invoke(remote: Boolean) {
        try {
            if (remote) {
                val token = bestEffort { gateway.accessTokenOrNull() } // awaits gateway init first
                if (token != null) {
                    // OSP-60 ASSUMPTION: /auth/logout is called on every remote teardown, also right after a role rejection (writes a LOGOUT audit for the rejected user) — see docs/osp-60-contract-assumptions.md
                    bestEffort { withTimeoutOrNull(remoteTimeoutMs) { backend.logout(token) } }
                }
                // supabase-kt signOut() does not clear local data on network errors, so never rely on it.
                bestEffort { withTimeoutOrNull(remoteTimeoutMs) { gateway.signOut() } }
            }
        } finally {
            withContext(NonCancellable) {
                // Wait for a pending session load, else the wipe could be undone by it (design 3.2).
                bestEffort { gateway.awaitInitialized() }
                bestEffort { gateway.clearLocalSession() }
                store.clearAuth()
            }
        }
    }

    private suspend fun <T> bestEffort(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
