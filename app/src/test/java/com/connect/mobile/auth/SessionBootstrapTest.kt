package com.connect.mobile.auth

import com.connect.mobile.data.session.StoredGrant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SessionBootstrapTest {
    private val events = Events()
    private val gateway = FakeGateway(events)
    private val backend = FakeBackend(events)
    private val store = InMemorySessionStore(events)
    private val bootstrap = SessionBootstrap(store, LogoutUseCase(gateway, backend, store))

    @Test fun grantLeadsToMissionActive() = runTest {
        val g = StoredGrant("u", "d", "rescuer", Instant.EPOCH, Instant.EPOCH.plusSeconds(60))
        store.writeGrant(g)
        store.session = "{}"
        assertEquals(BootstrapResult.MissionActive(g), bootstrap())
        assertTrue(events.isEmpty())
    }

    @Test fun sessionWithoutGrantIsWipedLocally() = runTest {
        store.session = "{}"
        assertEquals(BootstrapResult.LoginRequired, bootstrap())
        assertEquals(listOf("clearGrant", "clearLocalSession", "clearAuth"), events)
        assertNull(store.session)
    }

    @Test fun emptyStoreLeadsToLogin() = runTest {
        assertEquals(BootstrapResult.LoginRequired, bootstrap())
        assertTrue(events.isEmpty())
    }
}
