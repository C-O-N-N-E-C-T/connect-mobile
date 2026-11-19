package com.connect.mobile.auth

import com.connect.mobile.data.device.DeviceIdProvider
import com.connect.mobile.data.session.StoredGrant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class SessionBootstrapTest {
    private val events = Events()
    private val gateway = FakeGateway(events)
    private val backend = FakeBackend(events)
    private val store = InMemorySessionStore(events)
    private val bootstrap = SessionBootstrap(store, DeviceIdProvider(store) { "Phone" }, LogoutUseCase(gateway, backend, store))

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

    /** R3: the install device_id exists from the first launch, not only after the first login. */
    @Test fun firstLaunchCreatesDeviceId() = runTest {
        assertNull(store.deviceId)
        bootstrap()
        assertNotNull(store.deviceId)
        val first = store.deviceId
        bootstrap()
        assertEquals(first, store.deviceId)
    }

    /** R4: a failing device_id write (disk full) must not hide a stored grant. */
    @Test fun deviceIdWriteFailureStillReachesMission() = runTest {
        val g = StoredGrant("u", "d", "rescuer", Instant.EPOCH, Instant.EPOCH.plusSeconds(60))
        store.writeGrant(g)
        store.writeError = IOException("disk full")
        assertEquals(BootstrapResult.MissionActive(g), bootstrap())
    }
}
