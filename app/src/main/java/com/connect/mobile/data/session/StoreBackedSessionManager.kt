package com.connect.mobile.data.session

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json

/**
 * Routes supabase-kt session persistence into [SessionStore] so the tokens are not also written to
 * plaintext SharedPreferences by the default SettingsSessionManager.
 */
class StoreBackedSessionManager(private val store: SessionStore) : SessionManager {

    override suspend fun saveSession(session: UserSession) {
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
