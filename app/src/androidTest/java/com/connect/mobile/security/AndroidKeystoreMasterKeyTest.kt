package com.connect.mobile.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.connect.mobile.data.crypto.AndroidKeystoreMasterKey
import com.connect.mobile.data.crypto.MasterKeyMissingException
import com.google.crypto.tink.integration.android.AndroidKeystore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.crypto.AEADBadTagException

/**
 * OSP-75: gerçek Android Keystore anahtarı üretimi ve kalıcılığı. Cihazda/emülatörde çalışır:
 * ./gradlew connectedDebugAndroidTest
 *
 * Uygulamanın kendi alias'larına (connect_session_master_key / connect_db_master_key) DOKUNMAZ;
 * test başına rastgele alias kullanır ve sonunda siler.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreMasterKeyTest {
    private val alias = "connect_test_${UUID.randomUUID()}"

    @After fun deleteTestKey() {
        if (AndroidKeystore.hasKey(alias)) AndroidKeystore.deleteKey(alias)
    }

    @Test fun keyIsGeneratedOnceAndReusedByNewInstances() {
        assertFalse(AndroidKeystore.hasKey(alias))
        val ad = "ad".toByteArray()
        val ciphertext = AndroidKeystoreMasterKey(alias).getOrCreateAead().encrypt("secret".toByteArray(), ad)
        assertTrue(AndroidKeystore.hasKey(alias))
        // Yeni nesne = süreç yeniden başlamış gibi; anahtar Keystore'da kalıcı, aynı anahtar kullanılır.
        val plain = AndroidKeystoreMasterKey(alias).getExistingAead().decrypt(ciphertext, ad)
        assertArrayEquals("secret".toByteArray(), plain)
    }

    @Test fun getExistingDoesNotCreate() {
        try {
            AndroidKeystoreMasterKey(alias).getExistingAead()
            fail("expected MasterKeyMissingException")
        } catch (e: MasterKeyMissingException) {
            // beklenen
        }
        assertFalse(AndroidKeystore.hasKey(alias))
    }

    /** Sınıflandırmanın dayanağı: Keystore'da etiket hatası AEADBadTagException'dır (bozulma). */
    @Test fun tamperedCiphertextIsBadTagOnRealKeystore() {
        val aead = AndroidKeystoreMasterKey(alias).getOrCreateAead()
        val ad = "ad".toByteArray()
        val ct = aead.encrypt("secret".toByteArray(), ad)
        ct[ct.size - 1] = (ct[ct.size - 1].toInt() xor 1).toByte()
        try {
            aead.decrypt(ct, ad)
            fail("expected AEADBadTagException")
        } catch (e: AEADBadTagException) {
            // beklenen
        }
        try {
            aead.decrypt(aead.encrypt("x".toByteArray(), ad), "other".toByteArray())
            fail("expected AEADBadTagException")
        } catch (e: AEADBadTagException) {
            // beklenen
        }
    }

    @Test fun concurrentGetOrCreateYieldsOneUsableKey() {
        val ad = "ad".toByteArray()
        val results = (1..8).map { Thread { AndroidKeystoreMasterKey(alias).getOrCreateAead() } }
        results.forEach { it.start() }
        results.forEach { it.join() }
        val ct = AndroidKeystoreMasterKey(alias).getExistingAead().encrypt("v".toByteArray(), ad)
        assertArrayEquals("v".toByteArray(), AndroidKeystoreMasterKey(alias).getExistingAead().decrypt(ct, ad))
    }
}
