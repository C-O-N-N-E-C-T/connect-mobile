package com.connect.mobile.data.session

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Routes supabase-kt session persistence into [SessionStore] so the tokens are not also written to
 * plaintext SharedPreferences by the default SettingsSessionManager.
 *
 * Save gate (review R7): after a logout wipe ([blockSaves]) every save is ignored until the next
 * sign-in ([allowSaves]), so a supabase-kt refresh job that escaped cancellation cannot re-persist
 * tokens. Saves are allowed at process start, so a running mission keeps its refreshed tokens.
 */
class StoreBackedSessionManager(private val store: SessionStore) : SessionManager {
    @Volatile
    private var savesBlocked = false

    fun blockSaves() {
        savesBlocked = true
    }

    fun allowSaves() {
        savesBlocked = false
    }

    override suspend fun saveSession(session: UserSession) {
        if (savesBlocked) return
        // Review R4: supabase-kt also saves from its own refresh job, where no app code can catch a
        // store error (disk full). Drop a failed save: the session stays in memory only.
        try {
            store.writeSupabaseSession(json.encodeToString(UserSession.serializer(), session))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // dropped
        }
    }

    override suspend fun loadSession(): UserSession {
        val raw = store.readSupabaseSession() ?: throw NoSessionFoundException()
        return json.decodeFromString(UserSession.serializer(), raw)
    }

    /** Deletes ONLY the session; the grant and device_id are untouched. */
    override suspend fun deleteSession() {
        // Same reason as saveSession (supabase-kt clears a revoked session from its refresh job).
        // LogoutUseCase does not rely on this: its clearAuth() reports a store failure.
        try {
            store.deleteSupabaseSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ignored
        }
    }

    private companion object {
        // encodeDefaults = true is required by supabase-kt for UserSession round-trips.
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
