package com.connect.mobile.data.session

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
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
        store.writeSupabaseSession(json.encodeToString(UserSession.serializer(), session))
    }

    override suspend fun loadSession(): UserSession {
        val raw = store.readSupabaseSession() ?: throw NoSessionFoundException()
        return json.decodeFromString(UserSession.serializer(), raw)
    }

    /** Deletes ONLY the session; the grant and device_id are untouched. */
    override suspend fun deleteSession() {
        store.deleteSupabaseSession()
    }

    private companion object {
        // encodeDefaults = true is required by supabase-kt for UserSession round-trips.
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
