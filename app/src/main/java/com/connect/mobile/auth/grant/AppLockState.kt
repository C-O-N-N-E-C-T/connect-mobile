package com.connect.mobile.auth.grant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Kayıp telefon koruması (OSP-73). Süreç her başladığında KİLİTLİ başlar ("app restart"); uygulama
 * [timeoutMillis] süresinden UZUN arka planda kalırsa ön plana dönüşte yeniden kilitlenir. Süre monoton
 * saatle ölçülür (saat ayarıyla atlatılamaz). Kilidi başarılı biyometri/cihaz kimliği veya başarılı
 * çevrimiçi giriş (şifre + backend) açar.
 */
class AppLockState(
    private val clock: MonotonicClock,
    private val timeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MS,
) {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundSince: Long? = null

    @Synchronized
    fun onBackground() {
        if (backgroundSince == null) backgroundSince = clock.elapsedRealtimeMillis()
    }

    @Synchronized
    fun onForeground() {
        val since = backgroundSince ?: return
        backgroundSince = null
        if (clock.elapsedRealtimeMillis() - since > timeoutMillis) _locked.value = true
    }

    /** BiometricPrompt başarılı olunca veya başarılı çevrimiçi girişten hemen sonra çağrılır. */
    @Synchronized
    fun unlock() {
        _locked.value = false
    }

    /** Kurban, Röle ve tarama asla kilitlenmez (AC-01). */
    fun isGuarded(capability: Capability): Boolean = capability.biometricGuarded && _locked.value

    companion object {
        const val DEFAULT_LOCK_TIMEOUT_MS: Long = 15 * 60_000L
    }
}
