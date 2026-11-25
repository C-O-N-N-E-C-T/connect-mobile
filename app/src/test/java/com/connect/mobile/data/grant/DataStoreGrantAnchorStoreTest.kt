package com.connect.mobile.data.grant

import com.connect.mobile.auth.grant.GrantAnchor
import com.connect.mobile.data.crypto.TestCrypto
import com.connect.mobile.data.crypto.containsUtf8
import com.google.crypto.tink.Aead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreGrantAnchorStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val aead = TestCrypto.softwareAead()
    private val anchor = GrantAnchor(
        grantKey = "u1|device-1|1763625600000|1763884800000",
        bootCount = 7,
        elapsedAtAnchorMillis = 123_456L,
        consumedAtAnchorMillis = 3_600_000L,
        lastSeenWallMillis = 1_763_629_200_000L,
    )

    private fun file() = File(tmp.root, "anchor.enc")

    /** DataStore aynı dosya için tek etkin örnek ister: her blok kendi kapsamını kapatır. */
    private suspend fun <T> withStore(
        file: File = file(),
        key: Aead = aead,
        block: suspend (DataStoreGrantAnchorStore) -> T,
    ): T {
        val job = Job()
        try {
            val store = DataStoreGrantAnchorStore.create(
                produceFile = { file },
                aeadSupplier = { key },
                scope = CoroutineScope(Dispatchers.IO + job),
            )
            return block(store)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun emptyStoreReadsNull() = runTest {
        withStore { assertNull(it.read()) }
    }

    @Test fun writeThenRead() = runTest {
        withStore {
            it.write(anchor)
            assertEquals(anchor, it.read())
        }
    }

    @Test fun clearRemovesAnchor() = runTest {
        withStore {
            it.write(anchor)
            it.clear()
            assertNull(it.read())
        }
    }

    @Test fun persistsAcrossInstances() = runTest {
        withStore { it.write(anchor) }
        withStore { assertEquals(anchor, it.read()) }
    }

    @Test fun fileIsEncrypted() = runTest {
        withStore { it.write(anchor) }
        val bytes = file().readBytes()
        assertFalse(bytes.containsUtf8("device-1"))
        assertFalse(bytes.containsUtf8("anchor_grant_key"))
    }

    /** Anahtar değişti / dosya bozuk: çökmez, boş okunur (çapa duvar saatinden yeniden kurulur). */
    @Test fun unreadableFileReadsAsEmpty() = runTest {
        withStore { it.write(anchor) }
        withStore(key = TestCrypto.softwareAead()) { assertNull(it.read()) }
    }
}
