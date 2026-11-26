package com.connect.mobile.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.connect.mobile.ConnectApplication

/**
 * OSP-73: telefon açılışında grant süresini korur.
 *
 * - LOCKED_BOOT_COMPLETED (kullanıcı kilidi açmadan, saat henüz değiştirilemezken): o anki duvar
 *   saati [BootWallFloor]'a yazılır. "Uygulama kapalı 70 sa -> yeniden başlat -> saati geri al"
 *   sırasında monoton saat sıfırlansa da policy aradaki süreyi bu kayıttan görür.
 * - BOOT_COMPLETED (kilit açıldıktan sonra): kayıt tekrarlanır (LOCKED gelmediyse yedek) ve
 *   şifreli depolar artık açık olduğundan tam bir grant kontrolü çalışır (çapa güncellenir).
 *
 * "Zorla durdur" yapılmış uygulamaya sistem bu yayınları göndermez (tasarım notu §8).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> BootWallFloor(context).record(System.currentTimeMillis())
            Intent.ACTION_BOOT_COMPLETED -> {
                BootWallFloor(context).record(System.currentTimeMillis())
                val app = context.applicationContext as? ConnectApplication ?: return
                val pending = goAsync()
                app.container.recordGrantTime { pending.finish() }
            }
        }
    }
}
