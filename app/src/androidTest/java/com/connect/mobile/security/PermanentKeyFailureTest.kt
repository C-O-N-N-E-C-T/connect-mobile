package com.connect.mobile.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.connect.mobile.data.crypto.isPermanentKeyFailure
import com.connect.mobile.data.crypto.isTransientCryptoFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * OSP-75 (O2): KeyPermanentlyInvalidatedException yalnızca cihazda oluşturulabilir (JVM'de android.jar
 * taslağıdır). Kalıcı sayıldığı ve kontrol sırası gereği geçici yola düşmediği burada doğrulanır.
 */
@RunWith(AndroidJUnit4::class)
class PermanentKeyFailureTest {
    @Test fun keyPermanentlyInvalidatedIsPermanent() {
        val e = KeyPermanentlyInvalidatedException("invalidated")
        assertTrue(e.isPermanentKeyFailure())
        // Aynı zamanda GeneralSecurityException olduğu için kalıcı kontrolü önce yapılmalı.
        assertTrue(e.isTransientCryptoFailure())
        assertTrue(ProviderException(e).isPermanentKeyFailure())
        assertFalse(GeneralSecurityException("keystore busy").isPermanentKeyFailure())
    }
}
