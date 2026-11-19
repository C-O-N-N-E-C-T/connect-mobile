package com.connect.mobile.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.Instant

/**
 * TEMPORARY plaintext store (app-private DataStore file `connect_session.preferences_pb`).
 *
 * TODO(OSP-75): replace with Tink AEAD encrypted store.
 *
 * It only knows its own keys; it has no reference to any victim/relay data, so [clearAuth] cannot
 * touch unsynced victim records.
 */
class DataStoreSessionStore(private val dataStore: DataStore<Preferences>) : SessionStore {

    override suspend fun getOrCreateDeviceId(generate: () -> String): String {
        // Single edit = atomic read-modify-write; concurrent callers all see the same value.
        val prefs = dataStore.edit { p -> if (p[DEVICE_ID] == null) p[DEVICE_ID] = generate() }
        return checkNotNull(prefs[DEVICE_ID])
    }

    override suspend fun readSupabaseSession(): String? = dataStore.data.first()[SUPABASE_SESSION]

    override suspend fun writeSupabaseSession(json: String) {
        dataStore.edit { it[SUPABASE_SESSION] = json }
    }

    override suspend fun deleteSupabaseSession() {
        dataStore.edit { it.remove(SUPABASE_SESSION) }
    }

    override suspend fun readGrant(): StoredGrant? = dataStore.data.first().toGrant()

    override suspend fun writeGrant(grant: StoredGrant) {
        dataStore.edit {
            it[GRANT_USER_ID] = grant.userId
            it[GRANT_DEVICE_ID] = grant.deviceId
            it[GRANT_ROLE] = grant.role
            it[GRANT_AUTHENTICATED_AT] = grant.authenticatedAt.toEpochMilli()
            it[GRANT_EXPIRES_AT] = grant.expiresAt.toEpochMilli()
        }
    }

    override suspend fun clearGrant() {
        dataStore.edit { p -> GRANT_KEYS.forEach { p.remove(it) } }
    }

    override suspend fun clearAuth() {
        // Explicit key list; never clear() and never delete the file. DEVICE_ID is intentionally absent.
        dataStore.edit { p -> AUTH_KEYS.forEach { p.remove(it) } }
    }

    override val grant: Flow<StoredGrant?> = dataStore.data.map { it.toGrant() }

    private fun Preferences.toGrant(): StoredGrant? {
        val userId = this[GRANT_USER_ID] ?: return null
        val deviceId = this[GRANT_DEVICE_ID] ?: return null
        val role = this[GRANT_ROLE] ?: return null
        val authenticatedAt = this[GRANT_AUTHENTICATED_AT] ?: return null
        val expiresAt = this[GRANT_EXPIRES_AT] ?: return null
        return StoredGrant(
            userId = userId,
            deviceId = deviceId,
            role = role,
            authenticatedAt = Instant.ofEpochMilli(authenticatedAt),
            expiresAt = Instant.ofEpochMilli(expiresAt),
        )
    }

    companion object {
        const val FILE_NAME = "connect_session.preferences_pb"

        internal val DEVICE_ID = stringPreferencesKey("device_id")
        internal val SUPABASE_SESSION = stringPreferencesKey("supabase_session")
        internal val GRANT_USER_ID = stringPreferencesKey("grant_user_id")
        internal val GRANT_DEVICE_ID = stringPreferencesKey("grant_device_id")
        internal val GRANT_ROLE = stringPreferencesKey("grant_role")
        internal val GRANT_AUTHENTICATED_AT = longPreferencesKey("grant_authenticated_at")
        internal val GRANT_EXPIRES_AT = longPreferencesKey("grant_expires_at")

        private val GRANT_KEYS: List<Preferences.Key<*>> = listOf(
            GRANT_USER_ID, GRANT_DEVICE_ID, GRANT_ROLE, GRANT_AUTHENTICATED_AT, GRANT_EXPIRES_AT,
        )
        private val AUTH_KEYS: List<Preferences.Key<*>> = listOf(SUPABASE_SESSION) + GRANT_KEYS

        /** [produceFile] lets tests use a temp file; the app passes `filesDir/datastore/FILE_NAME`. */
        fun create(
            produceFile: () -> File,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ): DataStoreSessionStore =
            DataStoreSessionStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = produceFile))
    }
}
