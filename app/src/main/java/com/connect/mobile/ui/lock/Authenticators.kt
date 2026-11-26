package com.connect.mobile.ui.lock

import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * "Biometric or device PIN/pattern" (OSP-73). STRONG|DEVICE_CREDENTIAL Android 11 (API 30) öncesinde
 * desteklenmez; API 26–29'da WEAK|DEVICE_CREDENTIAL tek geçerli birleşimdir.
 */
internal fun allowedAuthenticators(sdkInt: Int = Build.VERSION.SDK_INT): Int =
    if (sdkInt >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
