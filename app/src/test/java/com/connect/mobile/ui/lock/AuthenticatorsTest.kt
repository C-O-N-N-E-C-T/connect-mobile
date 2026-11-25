package com.connect.mobile.ui.lock

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthenticatorsTest {
    @Test fun api30AndAboveUsesStrongOrCredential() {
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, allowedAuthenticators(30))
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, allowedAuthenticators(37))
    }

    /** STRONG|DEVICE_CREDENTIAL API 28–29'da desteklenmez; minSdk 26. */
    @Test fun belowApi30UsesWeakOrCredential() {
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, allowedAuthenticators(26))
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, allowedAuthenticators(29))
    }
}
