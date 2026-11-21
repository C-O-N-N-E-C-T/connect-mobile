package com.connect.mobile.data.session

import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant

class StoreBackedSessionManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val session = UserSession(
        accessToken = "test-access",
        refreshToken = "test-refresh",
        expiresIn = 3600,
        tokenType = "bearer",
        user = null,
    )

    @Test fun roundTrip() = runTest {
        val h = TestStoreHandle(tmp.newFile("m.preferences_pb").also { it.delete() })
        val m = StoreBackedSessionManager(h.store)
        m.saveSession(session)
        val loaded = m.loadSession()
        assertEquals(session.accessToken, loaded.accessToken)
        assertEquals(session.refreshToken, loaded.refreshToken)
        assertEquals(session.expiresIn, loaded.expiresIn)
        h.close()
    }

    @Test(expected = NoSessionFoundException::class)
    fun emptyLoadThrows() = runTest {
        val h = TestStoreHandle(tmp.newFile("m.preferences_pb").also { it.delete() })
        try {
            StoreBackedSessionManager(h.store).loadSession()
        } finally {
            h.close()
        }
    }

    @Test fun savesAreIgnoredAfterLogoutUntilNextSignIn() = runTest {
        val h = TestStoreHandle(tmp.newFile("m.preferences_pb").also { it.delete() })
        val m = StoreBackedSessionManager(h.store)
        m.blockSaves()
        m.saveSession(session) // e.g. a refresh job that escaped cancellation
        assertNull(h.store.readSupabaseSession())
        m.allowSaves()
        m.saveSession(session)
        assertNotNull(h.store.readSupabaseSession())
        h.close()
    }

    /** R4: supabase-kt saves/clears from its own refresh job, where a store error would be uncaught. */
    @Test fun storeErrorsDoNotEscape() = runTest {
        val failing = object : SessionStore by com.connect.mobile.auth.InMemorySessionStore() {
            override suspend fun writeSupabaseSession(json: String) = throw IOException("disk full")
            override suspend fun deleteSupabaseSession() = throw IOException("disk full")
        }
        val m = StoreBackedSessionManager(failing)
        m.saveSession(session)
        m.deleteSession()
    }

    @Test fun deleteSessionKeepsGrantAndDeviceId() = runTest {
        val h = TestStoreHandle(tmp.newFile("m.preferences_pb").also { it.delete() })
        val id = h.store.getOrCreateDeviceId { "dev" }
        val grant = StoredGrant("u", "dev", "rescuer", Instant.EPOCH, Instant.EPOCH.plusSeconds(60))
        h.store.writeGrant(grant)
        val m = StoreBackedSessionManager(h.store)
        m.saveSession(session)
        m.deleteSession()
        assertNull(h.store.readSupabaseSession())
        assertNotNull(h.store.readGrant())
        assertEquals(id, h.store.getOrCreateDeviceId { "other" })
        h.close()
    }
}
