package com.connect.mobile.data.session

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Local persistence for the Field Rescuer auth state: the install device_id, the supabase-kt session
 * (raw JSON owned by supabase-kt) and the 72 h offline mission grant.
 *
 * OSP-75 replaces the implementation with an encrypted one; callers only depend on this interface.
 */
interface SessionStore {
    /** Returns the install device_id, creating it atomically with [generate] on first use. */
    suspend fun getOrCreateDeviceId(generate: () -> String): String

    suspend fun readSupabaseSession(): String?
    suspend fun writeSupabaseSession(json: String)

    /** Removes ONLY the supabase session key. */
    suspend fun deleteSupabaseSession()

    suspend fun readGrant(): StoredGrant?
    suspend fun writeGrant(grant: StoredGrant)

    /** Removes the supabase session and grant keys ONLY. device_id and any other data are kept. */
    suspend fun clearAuth()

    val grant: Flow<StoredGrant?>
}

data class StoredGrant(
    val userId: String,
    val deviceId: String,
    val role: String,
    val authenticatedAt: Instant,
    val expiresAt: Instant,
)
