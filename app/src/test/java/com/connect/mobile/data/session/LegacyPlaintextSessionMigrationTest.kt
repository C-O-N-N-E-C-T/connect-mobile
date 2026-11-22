package com.connect.mobile.data.session

import com.connect.mobile.data.crypto.TestCrypto
import com.connect.mobile.data.crypto.TransientFailingAead
import com.connect.mobile.data.crypto.containsUtf8
import com.google.crypto.tink.Aead
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant

/** OSP-75: OSP-74 düz metin deposunun (`connect_session.preferences_pb`) şifreli depoya taşınması. */
class LegacyPlaintextSessionMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val aead = TestCrypto.softwareAead()
    private val sessionJson = """{"access_token":"legacy-access-SECRET","refresh_token":"legacy-refresh-SECRET"}"""
    private val grant = StoredGrant(
        userId = "legacy-user",
        deviceId = "legacy-device",
        role = "rescuer",
        authenticatedAt = Instant.parse("2026-11-19T10:00:00Z"),
        expiresAt = Instant.parse("2026-11-22T10:00:00Z"),
    )

    private fun files(): Pair<File, File> {
        val dir = tmp.newFolder("datastore")
        return File(dir, DataStoreSessionStore.FILE_NAME) to File(dir, EncryptedSessionStore.FILE_NAME)
    }

    /** OSP-74 sürümünün yazdığı düz metin dosya (gerçek DataStoreSessionStore ile). */
    private suspend fun writeLegacy(file: File, deviceId: String = "legacy-device-id") {
        val h = TestStoreHandle(file)
        h.store.getOrCreateDeviceId { deviceId }
        h.store.writeSupabaseSession(sessionJson)
        h.store.writeGrant(grant)
        h.close()
        assertTrue(file.readBytes().containsUtf8("legacy-access-SECRET")) // ön koşul: gerçekten düz metin
    }

    private fun encrypted(enc: File, legacy: File, a: Aead = aead) = TestStoreHandle(enc, aead = { a }, legacyFile = legacy)

    @Test fun migratesAllDataAndDeletesLegacyFile() = runTest {
        val (legacy, enc) = files()
        writeLegacy(legacy)

        val h = encrypted(enc, legacy)
        assertEquals("legacy-device-id", h.store.getOrCreateDeviceId { "new" })
        assertEquals(sessionJson, h.store.readSupabaseSession())
        assertEquals(grant, h.store.readGrant())
        h.close()

        assertFalse("legacy plaintext file must be deleted", legacy.exists())
        assertFalse(enc.readBytes().containsUtf8("legacy-access-SECRET"))
    }

    @Test fun secondRunDoesNotMigrateAgain() = runTest {
        val (legacy, enc) = files()
        writeLegacy(legacy)
        encrypted(enc, legacy).also { it.store.readGrant() }.close()

        // Taşımadan sonra oturum silindi (logout). Eski dosya yok; tekrar açılış veriyi geri getirmemeli.
        val h = encrypted(enc, legacy)
        h.store.clearAuth()
        h.close()
        val again = encrypted(enc, legacy)
        assertNull(again.store.readSupabaseSession())
        assertNull(again.store.readGrant())
        assertEquals("legacy-device-id", again.store.getOrCreateDeviceId { "new" })
        again.close()
        assertFalse(legacy.exists())
    }

    /** Yazma (şifreleme) başarısız: eski dosya SİLİNMEZ, sonraki açılışta taşıma tamamlanır. */
    @Test fun failedEncryptedWriteKeepsLegacyFile() = runTest {
        val (legacy, enc) = files()
        writeLegacy(legacy)

        val failing = encrypted(enc, legacy, TransientFailingAead())
        try {
            failing.store.readGrant()
            fail("expected IOException")
        } catch (e: IOException) {
            // beklenen
        }
        failing.close()
        assertTrue("legacy file must survive a failed migration", legacy.exists())
        assertFalse(enc.exists())

        val h = encrypted(enc, legacy)
        assertEquals(grant, h.store.readGrant())
        h.close()
        assertFalse(legacy.exists())
    }

    /**
     * Yarıda kesilme: şifreli dosya yazıldı ama eski dosya silinemeden süreç öldü. Sonraki açılış
     * şifreli değerleri korur (eski kopya onları ezmez), eksikleri tamamlar ve eski dosyayı siler.
     */
    @Test fun interruptedMigrationIsCompletedWithoutOverwriting() = runTest {
        val (legacy, enc) = files()
        writeLegacy(legacy)
        // Kesintiden önceki durumu taklit et: şifreli depoda zaten (daha yeni) bir oturum var.
        val pre = TestStoreHandle(enc, aead = { aead })
        pre.store.getOrCreateDeviceId { "legacy-device-id" }
        pre.store.writeSupabaseSession("""{"access_token":"refreshed-after-migration"}""")
        pre.close()

        val h = encrypted(enc, legacy)
        assertEquals("""{"access_token":"refreshed-after-migration"}""", h.store.readSupabaseSession())
        assertEquals("legacy-device-id", h.store.getOrCreateDeviceId { "new" })
        assertEquals(grant, h.store.readGrant()) // eksik olan eski kopyadan geldi
        h.close()
        assertFalse(legacy.exists())
    }

    @Test fun corruptLegacyFileIsDroppedNotCrashing() = runTest {
        val (legacy, enc) = files()
        legacy.writeBytes(ByteArray(32) { -1 })
        val h = encrypted(enc, legacy)
        assertNull(h.store.readGrant())
        h.close()
        assertFalse(legacy.exists())
    }

    @Test fun noLegacyFileIsNoop() = runTest {
        val (legacy, enc) = files()
        val h = encrypted(enc, legacy)
        assertNull(h.store.readGrant())
        assertEquals("fresh", h.store.getOrCreateDeviceId { "fresh" })
        h.close()
        assertFalse(legacy.exists())
    }

    /**
     * D3: eski dosya silinemedi ve içeriği hâlâ duruyor (ör. kesinti). Taşıma bayrağı sayesinde logout
     * (clearAuth) sonrası yeniden açılışta eski oturum/grant GERİ GELMEZ; bayrak clearAuth'tan sağ çıkar.
     */
    @Test fun migratedFlagPreventsResurrectionAfterLogout() = runTest {
        val (legacy, enc) = files()
        writeLegacy(legacy)
        val h = encrypted(enc, legacy)
        assertEquals(grant, h.store.readGrant())
        h.store.clearAuth()
        h.close()

        writeLegacy(legacy) // silinememiş eski kopya
        val again = encrypted(enc, legacy)
        assertNull(again.store.readSupabaseSession())
        assertNull(again.store.readGrant())
        assertEquals(true, again.dataStore.data.first()[LegacyPlaintextSessionMigration.MIGRATED])
        again.close()
        assertFalse(legacy.exists())
    }

    /** D3: silme başarısızsa (dizin yazılamaz) düz metin içerik sıfır bayta indirilir. */
    @Test fun undeletableLegacyFileIsTruncated() = runTest {
        val legacyDir = tmp.newFolder("legacy")
        val legacy = File(legacyDir, DataStoreSessionStore.FILE_NAME)
        val enc = File(tmp.newFolder("enc"), EncryptedSessionStore.FILE_NAME)
        writeLegacy(legacy)
        assertTrue(legacyDir.setWritable(false))
        try {
            val h = encrypted(enc, legacy)
            assertEquals(grant, h.store.readGrant())
            h.close()
            assertTrue(legacy.exists())
            assertEquals(0L, legacy.length())
        } finally {
            legacyDir.setWritable(true)
        }
    }
}
