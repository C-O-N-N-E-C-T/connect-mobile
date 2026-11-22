package com.connect.mobile.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.connect.mobile.data.crypto.AndroidKeystoreMasterKey
import com.connect.mobile.data.session.AeadSupplier
import com.connect.mobile.data.session.DataStoreSessionStore
import com.connect.mobile.data.session.EncryptedSessionStore
import com.connect.mobile.data.session.StoredGrant
import com.google.crypto.tink.integration.android.AndroidKeystore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * OSP-75: gerçek Keystore ile şifreli DataStore; dosya baytlarında düz metin yok ve "yeniden başlatma"
 * (yeni DataStore örneği) sonrası veri korunuyor. Uygulamanın gerçek dosyasına/alias'ına dokunmaz:
 * test cacheDir altında ayrı dosya ve rastgele alias kullanır.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedSessionStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "connect_test_${UUID.randomUUID()}"
    private val dir = File(context.cacheDir, "osp75-${UUID.randomUUID()}").also { it.mkdirs() }
    private val file = File(dir, EncryptedSessionStore.FILE_NAME)
    private val aead = AeadSupplier { AndroidKeystoreMasterKey(alias).getOrCreateAead() }

    private val accessToken = "device-test-access-SECRET"
    private val refreshToken = "device-test-refresh-SECRET"
    private val email = "device.test@example.org"
    private val userId = "0d6f4a1e-0000-4000-8000-0000000000aa"
    private val testGrant = StoredGrant(userId, "device-1", "rescuer", Instant.parse("2026-11-19T10:00:00Z"), Instant.parse("2026-11-22T10:00:00Z"))

    @After fun cleanUp() {
        dir.deleteRecursively()
        if (AndroidKeystore.hasKey(alias)) AndroidKeystore.deleteKey(alias)
    }

    private fun open(legacy: File? = null, job: Job) =
        EncryptedSessionStore.create({ file }, aead, legacy, CoroutineScope(Dispatchers.IO + job))

    @Test fun fileHasNoPlaintextAndSurvivesReopen() = runBlocking {
        val job = Job()
        val store = open(job = job)
        val id = store.getOrCreateDeviceId { "device-id-PLAIN" }
        store.writeSupabaseSession("""{"access_token":"$accessToken","refresh_token":"$refreshToken","user":{"id":"$userId","email":"$email"}}""")
        store.writeGrant(testGrant)
        job.cancelAndJoin()

        val bytes = file.readBytes()
        for (secret in listOf(accessToken, refreshToken, email, userId, "device-id-PLAIN", "supabase_session", "device_id")) {
            assertFalse("plaintext '$secret' on disk", bytes.containsUtf8(secret))
        }

        val job2 = Job()
        val again = open(job = job2)
        assertEquals(id, again.getOrCreateDeviceId { "other" })
        assertEquals(testGrant, again.readGrant())
        assertTrue(again.readSupabaseSession()!!.contains(accessToken))
        job2.cancelAndJoin()
    }

    @Test fun legacyPlaintextFileIsMigratedAndDeleted() = runBlocking {
        val legacy = File(dir, DataStoreSessionStore.FILE_NAME)
        val legacyJob = Job()
        DataStoreSessionStore.create({ legacy }, CoroutineScope(Dispatchers.IO + legacyJob)).apply {
            getOrCreateDeviceId { "legacy-id" }
            writeSupabaseSession("""{"access_token":"$accessToken"}""")
            writeGrant(testGrant)
        }
        legacyJob.cancelAndJoin()
        assertTrue(legacy.readBytes().containsUtf8(accessToken))

        val job = Job()
        val store = open(legacy, job)
        assertEquals("legacy-id", store.getOrCreateDeviceId { "new" })
        assertEquals(testGrant, store.readGrant())
        job.cancelAndJoin()
        assertFalse(legacy.exists())
        assertFalse(file.readBytes().containsUtf8(accessToken))
    }
}

internal fun ByteArray.containsUtf8(needle: String): Boolean {
    val n = needle.toByteArray(Charsets.UTF_8)
    outer@ for (i in 0..size - n.size) {
        for (j in n.indices) if (this[i + j] != n[j]) continue@outer
        return true
    }
    return false
}
