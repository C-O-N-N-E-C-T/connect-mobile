package com.connect.mobile.data.db

import com.connect.mobile.auth.Events
import com.connect.mobile.auth.FakeBackend
import com.connect.mobile.auth.FakeGateway
import com.connect.mobile.auth.LogoutUseCase
import com.connect.mobile.data.crypto.FakeMasterKey
import com.connect.mobile.data.crypto.containsUtf8
import com.connect.mobile.data.session.TestStoreHandle
import com.connect.mobile.data.crypto.TestCrypto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import com.connect.mobile.data.crypto.MasterKey
import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.UnrecoverableKeyException

/** OSP-75: SQLCipher parolasının üretimi, sarılması ve kalıcılığı (FR-R-11 / AC-28). */
class DbKeyProviderTest {
    @get:Rule val tmp = TemporaryFolder()

    // D1: parametre artık zorunlu; testlerde "şifreli DB yok".
    private val noData: () -> Boolean = { false }

    private fun keyFile() = File(tmp.newFolder("no_backup"), DbKeyProvider.FILE_NAME)

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun keyIs32BytesAndStableAcrossInstances() = runTest {
        val file = keyFile()
        val master = FakeMasterKey()
        val first = DbKeyProvider(file, master, noData).getOrCreateKey()
        assertEquals(32, first.size)
        assertTrue(file.exists())
        // "Yeniden başlatma": yeni sağlayıcı örneği, aynı dosya + aynı Keystore anahtarı.
        val second = DbKeyProvider(file, master, noData).getOrCreateKey()
        assertArrayEquals(first, second)
        assertEquals(1, master.generated)
    }

    @Test fun keyNeverOnDiskInPlaintext() = runTest {
        val file = keyFile()
        val key = DbKeyProvider(file, FakeMasterKey(), noData).getOrCreateKey()
        val disk = file.readBytes()
        assertFalse("raw key bytes on disk", disk.containsBytes(key))
        assertFalse("hex key on disk", disk.containsUtf8(key.hex()))
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test fun returnsFreshCopiesSoCallersCanWipe() = runTest {
        val p = DbKeyProvider(keyFile(), FakeMasterKey(), noData)
        val a = p.getOrCreateKey()
        val expected = a.copyOf()
        a.fill(0)
        assertArrayEquals(expected, p.getOrCreateKey())
    }

    @Test fun concurrentFirstCallsAgreeOnOneKey() = runTest {
        val file = keyFile()
        val master = FakeMasterKey()
        val p = DbKeyProvider(file, master, noData)
        val keys = (1..8).map { async { p.getOrCreateKey() } }.awaitAll()
        keys.forEach { assertArrayEquals(keys[0], it) }
        assertEquals(1, master.generated)
    }

    @Test fun corruptWrappedFileIsErrorAndNotRegenerated() = runTest {
        val file = keyFile()
        val master = FakeMasterKey()
        DbKeyProvider(file, master, noData).getOrCreateKey()
        val tampered = file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        file.writeBytes(tampered)

        assertUnrecoverable { DbKeyProvider(file, master, noData).getOrCreateKey() }
        // Yeni anahtar sessizce üretilip dosyanın üzerine YAZILMADI.
        assertArrayEquals(tampered, file.readBytes())
        assertEquals(1, master.generated)
    }

    @Test fun unknownFormatIsErrorAndNotRegenerated() = runTest {
        val file = keyFile().also { it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(1, 2, 3)) }
        val master = FakeMasterKey()
        assertUnrecoverable { DbKeyProvider(file, master, noData).getOrCreateKey() }
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertEquals(0, master.generated)
    }

    @Test fun lostMasterKeyIsErrorAndNotRegenerated() = runTest {
        val file = keyFile()
        val master = FakeMasterKey()
        DbKeyProvider(file, master, noData).getOrCreateKey()
        val before = file.readBytes()
        master.lose()

        assertUnrecoverable { DbKeyProvider(file, master, noData).getOrCreateKey() }
        assertArrayEquals(before, file.readBytes())
        assertEquals(1, master.generated) // Keystore'da da yeni anahtar üretilmedi
    }

    @Test fun transientKeystoreFailureIsIOExceptionAndFileKept() = runTest {
        val file = keyFile()
        val master = FakeMasterKey()
        val key = DbKeyProvider(file, master, noData).getOrCreateKey()
        val before = file.readBytes()

        master.failure = GeneralSecurityException("keystore busy")
        try {
            DbKeyProvider(file, master, noData).getOrCreateKey()
            fail("expected IOException")
        } catch (e: DbKeyUnrecoverableException) {
            fail("a transient failure is not unrecoverable")
        } catch (e: IOException) {
            // beklenen
        }
        assertArrayEquals(before, file.readBytes())

        master.failure = null
        assertArrayEquals(key, DbKeyProvider(file, master, noData).getOrCreateKey())
    }

    /** O2: kalıcı Keystore hataları geçici sayılmaz; anahtar yeniden üretilmez, Keystore anahtarı silinmez. */
    @Test fun permanentKeystoreFailuresAreUnrecoverable() = runTest {
        val file = keyFile()
        val master = FakeMasterKey()
        DbKeyProvider(file, master, noData).getOrCreateKey()
        val before = file.readBytes()
        val permanent = listOf(
            UnrecoverableKeyException("entry unreadable"),
            // Keystore bazen asıl nedeni ProviderException içinde sarar.
            GeneralSecurityException("wrapped", ProviderException(UnrecoverableKeyException("inner"))),
        )
        for (failure in permanent) {
            master.failure = failure
            assertUnrecoverable { DbKeyProvider(file, master, noData).getOrCreateKey() }
        }
        master.failure = null
        // AeadImpl'in SecretKey dönüşümü ClassCastException atarsa da kalıcıdır.
        val castFailing = object : MasterKey by master {
            override fun getExistingAead(): Aead = throw ClassCastException("not a SecretKey")
        }
        assertUnrecoverable { DbKeyProvider(file, castFailing, noData).getOrCreateKey() }

        assertArrayEquals(before, file.readBytes())
        assertEquals(1, master.generated)
        assertEquals(0, master.deleted)
    }

    @Test fun missingKeyFileWithExistingEncryptedDataIsError() = runTest {
        val file = keyFile()
        val master = FakeMasterKey()
        assertUnrecoverable { DbKeyProvider(file, master, encryptedDataExists = { true }).getOrCreateKey() }
        assertFalse(file.exists())
        assertEquals(0, master.generated)
    }

    /** clearAuth / logout / 403 akışı DB anahtar dosyasına dokunmaz (ayrı dosya, erişimleri yok). */
    @Test fun keySurvivesClearAuthAndLogout() = runTest {
        val dir = tmp.newFolder("app")
        val keyFile = File(dir, DbKeyProvider.FILE_NAME)
        val master = FakeMasterKey()
        val key = DbKeyProvider(keyFile, master, noData).getOrCreateKey()
        val wrapped = keyFile.readBytes()

        val aead = TestCrypto.softwareAead()
        val h = TestStoreHandle(File(dir, "connect_session.enc"), aead = { aead })
        h.store.getOrCreateDeviceId { "dev" }
        h.store.writeSupabaseSession("{}")
        h.store.clearAuth() // HTTP 403 / hesap kapatma yolu
        val events = Events()
        LogoutUseCase(FakeGateway(events), FakeBackend(events), h.store)(remote = true)
        h.close()

        assertArrayEquals(wrapped, keyFile.readBytes())
        assertArrayEquals(key, DbKeyProvider(keyFile, master, noData).getOrCreateKey())
    }

    @Test fun manualResetDeletesOnlyTheKeyFile() = runTest {
        val file = keyFile()
        val p = DbKeyProvider(file, FakeMasterKey(), noData)
        p.getOrCreateKey()
        p.deleteForManualReset()
        assertFalse(file.exists())
    }

    @Test fun sqlCipherRawKeyFormat() = runTest {
        val key = ByteArray(32) { it.toByte() }
        val raw = String(DbKeyProvider.toSqlCipherRawKey(key), Charsets.US_ASCII)
        assertEquals("x'" + key.hex() + "'", raw)
        assertEquals(67, raw.length)
        try {
            DbKeyProvider.toSqlCipherRawKey(ByteArray(16))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // beklenen
        }
        val p = DbKeyProvider(keyFile(), FakeMasterKey(), noData)
        val pass = String(p.sqlCipherPassphrase(), Charsets.US_ASCII)
        assertEquals("x'" + p.getOrCreateKey().hex() + "'", pass)
    }

    private suspend fun assertUnrecoverable(block: suspend () -> Unit) {
        try {
            block()
            fail("expected DbKeyUnrecoverableException")
        } catch (e: DbKeyUnrecoverableException) {
            // beklenen
        }
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
