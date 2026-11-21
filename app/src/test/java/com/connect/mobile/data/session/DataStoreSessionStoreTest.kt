package com.connect.mobile.data.session

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class DataStoreSessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val grant = StoredGrant(
        userId = "user-1",
        deviceId = "device-1",
        role = "rescuer",
        authenticatedAt = Instant.parse("2026-11-19T10:00:00Z"),
        expiresAt = Instant.parse("2026-11-22T10:00:00Z"),
    )

    @Test fun grantRoundTrip() = runTest {
        val h = TestStoreHandle(tmp.newFile("s.preferences_pb").also { it.delete() })
        assertNull(h.store.readGrant())
        h.store.writeGrant(grant)
        assertEquals(grant, h.store.readGrant())
        assertEquals(grant, h.store.grant.first())
        h.close()
    }

    @Test fun clearAuthKeepsDeviceIdForeignKeyAndFile() = runTest {
        val file = tmp.newFile("s.preferences_pb").also { it.delete() }
        val h = TestStoreHandle(file)
        val foreign = stringPreferencesKey("victim_sentinel")
        val id = h.store.getOrCreateDeviceId { "fixed-id" }
        h.store.writeSupabaseSession("{\"access_token\":\"x\"}")
        h.store.writeGrant(grant)
        // A key the store does not own (stands in for any other app data in the same file).
        h.dataStore.edit { it[foreign] = "keep-me" }

        h.store.clearAuth()

        assertNull(h.store.readGrant())
        assertNull(h.store.readSupabaseSession())
        assertEquals(id, h.store.getOrCreateDeviceId { "other" })
        assertEquals("keep-me", h.dataStore.data.first()[foreign])
        h.close()
        assertTrue(file.exists())
    }

    @Test fun clearGrantKeepsSessionAndDeviceId() = runTest {
        val h = TestStoreHandle(tmp.newFile("s.preferences_pb").also { it.delete() })
        val id = h.store.getOrCreateDeviceId { "fixed-id" }
        h.store.writeSupabaseSession("{}")
        h.store.writeGrant(grant)
        h.store.clearGrant()
        assertNull(h.store.readGrant())
        assertEquals("{}", h.store.readSupabaseSession())
        assertEquals(id, h.store.getOrCreateDeviceId { "other" })
        h.close()
    }

    /** R4: a corrupt file must not crash every launch; it fails closed and the device_id is regenerated. */
    @Test fun corruptFileIsReplacedInsteadOfCrashing() = runTest {
        val file = tmp.newFile("s.preferences_pb")
        val garbage = ByteArray(32) { -1 }
        file.writeBytes(garbage)

        // Control: without the handler, the same bytes really are a CorruptionException.
        val plain = TestStoreHandle(file)
        try {
            plain.store.readGrant()
            fail("expected CorruptionException")
        } catch (e: CorruptionException) {
            // expected
        }
        plain.close()

        file.writeBytes(garbage)
        val job = Job()
        val store = DataStoreSessionStore.create(produceFile = { file }, scope = CoroutineScope(Dispatchers.IO + job))
        assertNull(store.readGrant())
        assertNull(store.readSupabaseSession())
        assertEquals("regenerated", store.getOrCreateDeviceId { "regenerated" })
        job.cancelAndJoin()
    }

    @Test fun deleteSupabaseSessionKeepsGrant() = runTest {
        val h = TestStoreHandle(tmp.newFile("s.preferences_pb").also { it.delete() })
        h.store.writeSupabaseSession("{}")
        h.store.writeGrant(grant)
        h.store.deleteSupabaseSession()
        assertNull(h.store.readSupabaseSession())
        assertEquals(grant, h.store.readGrant())
        h.close()
    }
}
