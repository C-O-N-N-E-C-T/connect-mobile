package com.connect.mobile.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.connect.mobile.ConnectApplication
import com.connect.mobile.auth.grant.AndroidMonotonicClock

/**
 * OSP-73: telefon açılışında grant süresini korur.
 *
 * - LOCKED_BOOT_COMPLETED (kullanıcı kilidi açmadan, saat henüz değiştirilemezken): o anki duvar
 *   saati ve açılış sayacı [BootWallFloor]'a yazılır (önceki açılışın kaydının yerine). "Uygulama kapalı 70 sa -> yeniden başlat -> saati geri al"
 *   sırasında monoton saat sıfırlansa da policy aradaki süreyi bu kayıttan görür.
 * - BOOT_COMPLETED (kilit açıldıktan sonra): kayıt tekrarlanır (LOCKED gelmediyse yedek) ve
 *   şifreli depolar artık açık olduğundan tam bir grant kontrolü çalışır (çapa güncellenir).
 *
 * "Zorla durdur" yapılmış uygulamaya sistem bu yayınları göndermez (tasarım notu §8).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> recordBoot(context)
            Intent.ACTION_BOOT_COMPLETED -> {
                recordBoot(context) // LOCKED gelmediyse yedek; geldiyse ilk kayıt korunur
                val app = context.applicationContext as? ConnectApplication ?: return
                val pending = goAsync()
                app.container.recordGrantTime { pending.finish() }
            }
        }
    }

    private fun recordBoot(context: Context) {
        val bootCount = AndroidMonotonicClock(context.contentResolver).bootCount()
        BootWallFloor(context).recordIfAbsent(bootCount, System.currentTimeMillis())
    }
}
