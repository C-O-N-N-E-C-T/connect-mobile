package com.connect.mobile.data.session

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.connect.mobile.data.crypto.TestCrypto
import com.connect.mobile.data.crypto.TransientFailingAead
import com.connect.mobile.data.crypto.containsUtf8
import com.google.crypto.tink.Aead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.time.Instant

/** OSP-75: şifreli seri hale getirici + şifreli DataStore'un dosya düzeyi davranışı. */
class EncryptedSessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val aead = TestCrypto.softwareAead()

    // Diskte görünmemesi gereken değerler (sahte; gerçek token değil).
    private val accessToken = "test-access-token-SECRET-1"
    private val refreshToken = "test-refresh-token-SECRET-2"
    private val email = "rescuer.test@example.org"
    private val userId = "5f0c4a1e-0000-4000-8000-00000000abcd"
    private val sessionJson =
        """{"access_token":"$accessToken","refresh_token":"$refreshToken","expires_in":3600,"token_type":"bearer",""" +
            """"user":{"id":"$userId","email":"$email","aud":"authenticated"}}"""
    private val grant = StoredGrant(
        userId = userId,
        deviceId = "device-1",
        role = "rescuer",
        authenticatedAt = Instant.parse("2026-11-19T10:00:00Z"),
        expiresAt = Instant.parse("2026-11-22T10:00:00Z"),
    )

    private fun newFile(name: String = "s.enc") = tmp.newFile(name).also { it.delete() }

    private suspend fun serialize(serializer: EncryptedPreferencesSerializer): ByteArray {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("supabase_session") to sessionJson,
            longPreferencesKey("grant_expires_at") to 42L,
        )
        return ByteArrayOutputStream().also { serializer.writeTo(prefs, it) }.toByteArray()
    }

    @Test fun serializerRoundTripHidesValuesAndKeyNames() = runTest {
        val s = EncryptedPreferencesSerializer({ aead })
        val bytes = serialize(s)
        for (secret in listOf(accessToken, refreshToken, email, userId, "access_token", "supabase_session", "grant_expires_at")) {
            assertFalse("plaintext '$secret' on disk", bytes.containsUtf8(secret))
        }
        val back = s.readFrom(ByteArrayInputStream(bytes))
        assertEquals(sessionJson, back[stringPreferencesKey("supabase_session")])
        assertEquals(42L, back[longPreferencesKey("grant_expires_at")])
    }

    /** Seri hale getirici herhangi bir Tink AEAD ile çalışır (keyset + güncel getPrimitive overload'u). */
    @Test fun roundTripWithKeysetAead() = runTest {
        val keyset = TestCrypto.keysetAead()
        val s = EncryptedPreferencesSerializer({ keyset })
        val bytes = serialize(s)
        assertFalse(bytes.containsUtf8(accessToken))
        assertEquals(sessionJson, s.readFrom(ByteArrayInputStream(bytes))[stringPreferencesKey("supabase_session")])
    }

    @Test fun wrongAssociatedDataIsCorruption() = runTest {
        val bytes = serialize(EncryptedPreferencesSerializer({ aead }))
        val other = EncryptedPreferencesSerializer({ aead }, associatedData = "other_file.v1".toByteArray())
        assertCorruption { other.readFrom(ByteArrayInputStream(bytes)) }
    }

    @Test fun otherKeyIsCorruption() = runTest {
        val bytes = serialize(EncryptedPreferencesSerializer({ aead }))
        val otherKey = TestCrypto.softwareAead()
        assertCorruption { EncryptedPreferencesSerializer({ otherKey }).readFrom(ByteArrayInputStream(bytes)) }
    }

    @Test fun tamperedShortOrUnknownVersionIsCorruption() = runTest {
        val s = EncryptedPreferencesSerializer({ aead })
        val bytes = serialize(s)
        val flipped = bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertCorruption { s.readFrom(ByteArrayInputStream(flipped)) }
        assertCorruption { s.readFrom(ByteArrayInputStream(ByteArray(0))) }
        assertCorruption { s.readFrom(ByteArrayInputStream(bytes.copyOf(10))) }
        val wrongVersion = bytes.copyOf().also { it[0] = 9 }
        assertCorruption { s.readFrom(ByteArrayInputStream(wrongVersion)) }
    }

    @Test fun transientKeystoreFailureIsIOExceptionNotCorruption() = runTest {
        val bytes = serialize(EncryptedPreferencesSerializer({ aead }))
        val failing = EncryptedPreferencesSerializer({ TransientFailingAead() })
        try {
            failing.readFrom(ByteArrayInputStream(bytes))
            fail("expected IOException")
        } catch (e: CorruptionException) {
            fail("transient failure must not be treated as corruption")
        } catch (e: IOException) {
            // beklenen
        }
        try {
            failing.writeTo(emptyPreferences(), ByteArrayOutputStream())
            fail("expected IOException")
        } catch (e: IOException) {
            // beklenen
        }
        // Keystore'un kendisine ulaşılamaması (anahtar yüklenemedi) da geçicidir.
        val unavailable = EncryptedPreferencesSerializer({ throw java.security.KeyStoreException("locked") })
        try {
            unavailable.readFrom(ByteArrayInputStream(bytes))
            fail("expected IOException")
        } catch (e: CorruptionException) {
            fail("keystore unavailability must not be treated as corruption")
        } catch (e: IOException) {
            // beklenen
        }
    }

    /** O2: kalıcı anahtar hatası okumada bozulmadır (anahtar silinir), yazmada IOException. */
    @Test fun permanentKeyFailureDiscardsKeyAndIsCorruptionOnRead() = runTest {
        val bytes = serialize(EncryptedPreferencesSerializer({ aead }))
        for (failure in listOf<Exception>(
            UnrecoverableKeyException("entry unreadable"),
            ClassCastException("not a SecretKey"),
            ProviderException(UnrecoverableKeyException("wrapped")),
        )) {
            var discarded = 0
            val supplier = object : AeadSupplier {
                override fun get(): Aead = throw failure
                override fun discardKey() {
                    discarded++
                }
            }
            val s = EncryptedPreferencesSerializer(supplier)
            assertCorruption { s.readFrom(ByteArrayInputStream(bytes)) }
            try {
                s.writeTo(emptyPreferences(), ByteArrayOutputStream())
                fail("expected IOException")
            } catch (e: IOException) {
                // beklenen
            }
            assertEquals(failure.toString(), 2, discarded)
        }
    }

    /** Depo düzeyi: kalıcı hata -> fail-closed boş depo (giriş gerekir), sonra yeni anahtarla çalışır. */
    @Test fun permanentKeyFailureFailsClosedAndRecoversWithNewKey() = runTest {
        val file = newFile()
        val h = TestStoreHandle(file, aead = { aead })
        h.store.writeGrant(grant)
        h.close()

        var current: Aead? = null // null = anahtar kalıcı olarak bozuk
        var discarded = 0
        val supplier = object : AeadSupplier {
            override fun get(): Aead = current ?: throw UnrecoverableKeyException("invalidated")
            override fun discardKey() {
                discarded++
                current = TestCrypto.softwareAead() // Keystore: silinen alias yeniden üretilir
            }
        }
        val job = Job()
        val store = EncryptedSessionStore.create(produceFile = { file }, aeadSupplier = supplier, scope = CoroutineScope(Dispatchers.IO + job))
        assertNull(store.readGrant())
        assertEquals(1, discarded)
        store.writeGrant(grant)
        assertEquals(grant, store.readGrant())
        job.cancelAndJoin()
    }

    @Test fun storeFileHasNoPlaintextAndSurvivesRestart() = runTest {
        val file = newFile()
        val h = TestStoreHandle(file, aead = { aead })
        val id = h.store.getOrCreateDeviceId { "device-id-PLAIN-123" }
        h.store.writeSupabaseSession(sessionJson)
        h.store.writeGrant(grant)
        h.close()

        val bytes = file.readBytes()
        for (secret in listOf(accessToken, refreshToken, email, userId, "device-id-PLAIN-123", "rescuer", "device_id", "grant_user_id")) {
            assertFalse("plaintext '$secret' on disk", bytes.containsUtf8(secret))
        }

        // "Yeniden başlatma": yeni DataStore örneği aynı dosya + aynı anahtar.
        val again = TestStoreHandle(file, aead = { aead })
        assertEquals(id, again.store.getOrCreateDeviceId { "other" })
        assertEquals(sessionJson, again.store.readSupabaseSession())
        assertEquals(grant, again.store.readGrant())
        again.close()
    }

    /** Geçici Keystore hatası: okuma IOException verir, dosya silinmez/değişmez, hata geçince veri yerinde. */
    @Test fun transientFailureKeepsFileAndData() = runTest {
        val file = newFile()
        val h = TestStoreHandle(file, aead = { aead })
        h.store.writeGrant(grant)
        h.close()
        val before = file.readBytes()

        val job = Job()
        // Uygulamadaki gibi bozulma işleyicisi AÇIK: yine de geçici hata dosyayı boşaltmamalı.
        var current: Aead = TransientFailingAead()
        val store = EncryptedSessionStore.create(produceFile = { file }, aeadSupplier = { current }, scope = CoroutineScope(Dispatchers.IO + job))
        try {
            store.readGrant()
            fail("expected IOException")
        } catch (e: IOException) {
            // beklenen
        }
        try {
            store.writeSupabaseSession("{}")
            fail("expected IOException")
        } catch (e: IOException) {
            // beklenen
        }
        assertTrue(file.exists())
        assertArrayEquals(before, file.readBytes())
        job.cancelAndJoin()

        // Keystore toparlandı: aynı veri okunur.
        current = aead
        val recovered = TestStoreHandle(file, aead = { current })
        assertEquals(grant, recovered.store.readGrant())
        recovered.close()
    }

    /** Ana anahtar kayboldu (yeni anahtar): eski dosya doğrulanamaz -> fail-closed boş depo, çökme yok. */
    @Test fun lostMasterKeyFailsClosedToEmpty() = runTest {
        val file = newFile()
        val h = TestStoreHandle(file, aead = { aead })
        h.store.writeGrant(grant)
        h.close()

        val job = Job()
        val newKey = TestCrypto.softwareAead()
        val store = EncryptedSessionStore.create(produceFile = { file }, aeadSupplier = { newKey }, scope = CoroutineScope(Dispatchers.IO + job))
        assertNull(store.readGrant())
        job.cancelAndJoin()
    }

    private suspend fun assertCorruption(block: suspend () -> Unit) {
        try {
            block()
            fail("expected CorruptionException")
        } catch (e: CorruptionException) {
            // beklenen
        }
    }
}
