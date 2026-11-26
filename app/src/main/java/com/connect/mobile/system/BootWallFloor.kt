package com.connect.mobile.system

import android.content.Context
import androidx.core.content.edit
import com.connect.mobile.auth.grant.OfflineGrantPolicy

/**
 * OSP-73: telefon her açıldığında kaydedilen duvar saati (yalnızca artar). OfflineGrantPolicy bunu
 * "gerçekte en az bu ana gelindi" alt sınırı olarak kullanır.
 *
 * Cihaz korumalı depoda (device-protected storage) tutulur: LOCKED_BOOT_COMPLETED anında, kullanıcı
 * kilidi açıp saati değiştiremeden ÖNCE yazılabilsin diye. İçerik gizli değildir (yalnızca bir zaman
 * damgası); başka uygulamalar yazamaz, root'lu cihaz sınırı tasarım notu §8'de.
 */
class BootWallFloor(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): Long = prefs.getLong(KEY_WALL_MS, OfflineGrantPolicy.NO_WALL_FLOOR)

    /** Yalnızca daha büyük bir değer yazar; geri alınmış saat kaydı küçültemez. */
    @Synchronized
    fun record(wallMillis: Long) {
        // commit (senkron): receiver dönmeden kayıt diske yazılmış olmalı.
        if (wallMillis > read()) prefs.edit(commit = true) { putLong(KEY_WALL_MS, wallMillis) }
    }

    companion object {
        private const val PREFS_NAME = "connect_boot_wall_floor"
        private const val KEY_WALL_MS = "max_boot_wall_ms"
    }
}
