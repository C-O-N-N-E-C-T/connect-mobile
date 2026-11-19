package com.connect.mobile.data.device

import com.connect.mobile.data.session.TestStoreHandle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class DeviceIdProviderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file() = tmp.newFile("d.preferences_pb").also { it.delete() }

    @Test fun createdOnceAndValidV4Uuid() = runTest {
        val h = TestStoreHandle(file())
        val p = DeviceIdProvider(h.store) { "Test Phone" }
        val first = p.deviceId()
        assertEquals(first, p.deviceId())
        assertEquals(4, UUID.fromString(first).version())
        h.close()
    }

    @Test fun stableAcrossStoreInstances() = runTest {
        val f = file()
        val h1 = TestStoreHandle(f)
        val id = DeviceIdProvider(h1.store) { "x" }.deviceId()
        h1.close()
        val h2 = TestStoreHandle(f)
        assertEquals(id, DeviceIdProvider(h2.store) { "x" }.deviceId())
        h2.close()
    }

    @Test fun concurrentCallsReturnOneValue() = runTest {
        val h = TestStoreHandle(file())
        val p = DeviceIdProvider(h.store) { "x" }
        val ids = (1..20).map { async { p.deviceId() } }.awaitAll().toSet()
        assertEquals(1, ids.size)
        h.close()
    }

    @Test fun deviceNameTrimmedTo100() {
        val h = TestStoreHandle(file())
        val name = DeviceIdProvider(h.store) { "  " + "A".repeat(150) + " " }.deviceName()
        assertEquals(100, name.length)
        assertTrue(name.all { it == 'A' })
    }
}
