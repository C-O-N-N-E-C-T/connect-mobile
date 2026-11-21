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
 * Order: (1) delete the grant keys at once, so a process death during the slow remote part can no
 * longer leave offline access behind; (2) best-effort remote calls with the in-memory token;
 * (3) always wipe the Supabase session and every auth key.
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
    /** Returns true when the local auth keys were removed (false only if the store itself failed). */
    suspend operator fun invoke(remote: Boolean): Boolean {
        var wiped = false
        try {
            // 1. The grant is the offline-access gate; deleting it does not touch the in-memory
            //    Supabase session that the remote calls below still need.
            withContext(NonCancellable) { quietly { store.clearGrant() } }
            if (remote) {
                // 2. Token snapshot in memory (awaits gateway init first), then best-effort remote calls.
                val token = bestEffort { gateway.accessTokenOrNull() }
                if (token != null) {
                    // OSP-60 ASSUMPTION: /auth/logout is called on every remote teardown, also right after a role rejection (writes a LOGOUT audit for the rejected user) — see docs/osp-60-contract-assumptions.md
                    bestEffort { withTimeoutOrNull(remoteTimeoutMs) { backend.logout(token) } }
                }
                // supabase-kt signOut() does not clear local data on network errors, so never rely on it.
                bestEffort { withTimeoutOrNull(remoteTimeoutMs) { gateway.signOut() } }
            }
        } finally {
            // 3. Every step runs unconditionally and nothing escapes, so a failing or cancelling
            //    callee can never skip clearAuth().
            withContext(NonCancellable) {
                // Wait for a pending session load, else the wipe could be undone by it (design 3.2).
                quietly { gateway.awaitInitialized() }
                quietly { gateway.clearLocalSession() }
                wiped = quietly { store.clearAuth() } != null
            }
        }
        return wiped
    }

    private suspend fun <T> bestEffort(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** For the wipe only: swallows everything, including a CancellationException thrown by a callee. */
    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        null
    }
}
