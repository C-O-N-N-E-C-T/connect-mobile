package com.connect.mobile.system

import android.content.Context
import androidx.core.content.edit
import com.connect.mobile.auth.grant.BootWallRecord
import com.connect.mobile.auth.grant.MonotonicClock

/**
 * OSP-73: bu açılışın duvar saati kaydı ([BootWallRecord]). OfflineGrantPolicy bunu yalnızca aynı açılışta
 * ve yeniden başlatma sonrası "gerçekte en az bu ana gelindi" alt sınırı olarak kullanır.
 *
 * Her açılışta bir kez, ilk gelen yayında yazılır (LOCKED_BOOT_COMPLETED; gelmezse BOOT_COMPLETED) ve
 * önceki açılışın kaydının yerine geçer: yanlış bir kayıt (ör. ileri tarihli açılış) bir sonraki açılışta
 * kaybolur, yeni grant'leri hiç etkilemez.
 *
 * Cihaz korumalı depoda tutulur: LOCKED_BOOT_COMPLETED anında, kullanıcı kilidi açıp saati değiştiremeden
 * ÖNCE yazılabilsin diye. İçerik gizli değildir (zaman damgası); başka uygulamalar yazamaz, root'lu cihaz
 * sınırı tasarım notu §8'de.
 */
class BootWallFloor(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): BootWallRecord? {
        if (!prefs.contains(KEY_BOOT_COUNT) || !prefs.contains(KEY_WALL_MS)) return null
        return BootWallRecord(
            bootCount = prefs.getInt(KEY_BOOT_COUNT, MonotonicClock.UNKNOWN_BOOT),
            wallMillis = prefs.getLong(KEY_WALL_MS, 0L),
        )
    }

    /**
     * Bu açılış için kayıt yoksa yazar. Açılış sayacı okunamıyorsa yazmaz (kayıt bir açılışa
     * bağlanamaz). Aynı açılışta ikinci yayın (BOOT_COMPLETED) ilk kaydı değiştirmez: ilk kayıt,
     * kullanıcı kilidi açmadan alındığı için daha güvenilirdir.
     */
    @Synchronized
    fun recordIfAbsent(bootCount: Int, wallMillis: Long) {
        if (bootCount == MonotonicClock.UNKNOWN_BOOT) return
        if (read()?.bootCount == bootCount) return
        // commit (senkron): receiver dönmeden kayıt diske yazılmış olmalı.
        prefs.edit(commit = true) {
            putInt(KEY_BOOT_COUNT, bootCount)
            putLong(KEY_WALL_MS, wallMillis)
        }
    }

    companion object {
        private const val PREFS_NAME = "connect_boot_wall_floor"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_WALL_MS = "boot_wall_ms"
    }
}
