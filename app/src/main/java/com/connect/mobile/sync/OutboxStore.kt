package com.connect.mobile.sync

import kotlinx.serialization.json.JsonObject

/** A queued victim record waiting for upload. [payload] is sent unchanged in POST /api/v1/sync. */
data class OutboxRecord(val id: String, val payload: JsonObject)

/**
 * OSP-76: the reconnection gate's view of the local upload queue.
 *
 * The queue itself (the encrypted Room/SQLCipher database) belongs to Epic E8, not to OSP-76.
 * Contract the gate relies on: a record leaves the queue ONLY through [markSent], after the backend
 * confirmed the upload. Network errors, 401 and 403 never remove anything.
 */
interface OutboxStore {
    /** Queued records, oldest first. */
    suspend fun pending(): List<OutboxRecord>

    /** Called only after a successful upload of [id]. */
    suspend fun markSent(id: String)
}

/**
 * Placeholder until E8 provides the encrypted queue: nothing is queued, so nothing is uploaded.
 * TODO(E8): replace with the Room/SQLCipher-backed store.
 */
object EmptyOutboxStore : OutboxStore {
    override suspend fun pending(): List<OutboxRecord> = emptyList()
    override suspend fun markSent(id: String) = Unit
}
