package com.connect.mobile.auth.grant

import android.content.ContentResolver
import android.os.SystemClock
import android.provider.Settings

/**
 * Kullanıcının değiştiremediği saat (OSP-73). Telefon açıldığından beri geçen süre; ayarlardan saat
 * geri alınsa da geri gitmez. Yeniden başlatmada sıfırlanır, bu yüzden açılış sayacı da verilir.
 */
interface MonotonicClock {
    fun elapsedRealtimeMillis(): Long

    /** Açılış sayacı; okunamazsa [UNKNOWN_BOOT]. */
    fun bootCount(): Int

    companion object {
        const val UNKNOWN_BOOT = -1
    }
}

class AndroidMonotonicClock(private val contentResolver: ContentResolver) : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()

    override fun bootCount(): Int = try {
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Settings.SettingNotFoundException) {
        MonotonicClock.UNKNOWN_BOOT
    }
}
