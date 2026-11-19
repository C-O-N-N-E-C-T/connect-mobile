package com.connect.mobile.auth

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.connect.mobile.data.session.StoredGrant
import com.connect.mobile.data.session.TestStoreHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LogoutUseCaseTest {
    @get:Rule val tmp = TemporaryFolder()

    private val events = Events()
    private val gateway = FakeGateway(events)
    private val backend = FakeBackend(events)
    private val grant = StoredGrant("u1", "dev", "rescuer", Instant.EPOCH, Instant.EPOCH.plusSeconds(3600))
    private val sentinel = stringPreferencesKey("victim_sync_cursor")

    private suspend fun seeded(): TestStoreHandle {
        val h = TestStoreHandle(tmp.newFile("s.preferences_pb").also { it.delete() })
        h.store.getOrCreateDeviceId { "install-id" }
        h.store.writeSupabaseSession("{}")
        h.store.writeGrant(grant)
        h.dataStore.edit { it[sentinel] = "keep" }
        return h
    }

    private suspend fun assertWipedButKept(h: TestStoreHandle) {
        assertNull(h.store.readGrant())
        assertNull(h.store.readSupabaseSession())
        assertEquals("install-id", h.store.getOrCreateDeviceId { "new" })
        assertEquals("keep", h.dataStore.data.first()[sentinel])
    }

    @Test fun remoteOrderAndScopedWipeKeepVictimData() = runTest {
        val victimDb = tmp.newFile("victims.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val h = seeded()
        LogoutUseCase(gateway, backend, RecordingSessionStore(h.store, events))(remote = true)
        assertEquals(listOf("clearGrant", "backendLogout", "signOut", "clearLocalSession", "clearAuth"), events)
        assertWipedButKept(h)
        assertArrayEquals(byteArrayOf(1, 2, 3), victimDb.readBytes())
        h.close()
    }

    @Test fun offlineFailuresStillWipe() = runTest {
        val h = seeded()
        backend.logoutBehavior = { throw IOException("offline") }
        gateway.signOutError = IOException("HttpRequestException: offline")
        LogoutUseCase(gateway, backend, h.store)(remote = true)
        assertWipedButKept(h)
        h.close()
    }

    @Test fun hangingBackendIsBoundedAndStillWipes() = runTest {
        val h = seeded()
        backend.logoutBehavior = { awaitCancellation() }
        LogoutUseCase(gateway, backend, h.store)(remote = true)
        assertTrue(events.contains("clearLocalSession"))
        assertWipedButKept(h)
        h.close()
    }

    @Test fun noTokenSkipsBackendButWipes() = runTest {
        val h = seeded()
        gateway.token = null
        LogoutUseCase(gateway, backend, h.store)(remote = true)
        assertTrue("backendLogout" !in events)
        assertWipedButKept(h)
        h.close()
    }

    @Test fun localOnlyMakesNoRemoteCalls() = runTest {
        val h = seeded()
        LogoutUseCase(gateway, backend, h.store)(remote = false)
        assertEquals(listOf("clearLocalSession"), events)
        assertWipedButKept(h)
        h.close()
    }

    @Test fun callerCancellationStillCompletesWipe() = runTest {
        val h = seeded()
        backend.logoutBehavior = { awaitCancellation() }
        val job = launch { LogoutUseCase(gateway, backend, h.store)(remote = true) }
        advanceTimeBy(1_000)
        job.cancel()
        job.join()
        assertTrue(events.contains("clearLocalSession"))
        assertWipedButKept(h)
        h.close()
    }

    /** R1: a process death during the remote part must not leave the grant behind. */
    @Test fun grantIsDeletedBeforeAnyRemoteCall() = runTest {
        val h = seeded()
        var grantDuringRemote: StoredGrant? = grant
        var sessionDuringRemote: String? = null
        backend.logoutBehavior = {
            grantDuringRemote = h.store.readGrant()
            sessionDuringRemote = h.store.readSupabaseSession()
            throw IllegalStateException("process dies here")
        }
        LogoutUseCase(gateway, backend, h.store)(remote = true)
        assertNull(grantDuringRemote)
        assertEquals("{}", sessionDuringRemote) // tokens are still there for the remote logout
        assertWipedButKept(h)
        h.close()
    }

    @Test fun cancellationFromCalleeInsideWipeStillClearsAuth() = runTest {
        val h = seeded()
        gateway.clearError = CancellationException("supabase-kt internal job cancelled")
        val wiped = LogoutUseCase(gateway, backend, h.store)(remote = true)
        assertTrue(wiped)
        assertWipedButKept(h)
        h.close()
    }

    @Test fun failingClearLocalSessionStillClearsAuth() = runTest {
        val h = seeded()
        gateway.clearError = IllegalStateException("boom")
        LogoutUseCase(gateway, backend, h.store)(remote = false)
        assertWipedButKept(h)
        h.close()
    }

    @Test fun waitsForGatewayInitialisationBeforeClearing() = runTest {
        val h = seeded()
        gateway.initDelayMs = 3_000
        LogoutUseCase(gateway, backend, h.store)(remote = false)
        assertEquals(true, gateway.clearedAfterInit)
        h.close()
    }
}
