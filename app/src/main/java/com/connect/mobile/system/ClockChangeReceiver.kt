package com.connect.mobile.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.connect.mobile.ConnectApplication

/**
 * OSP-73: duvar saati elle değiştirildiğinde (ACTION_TIME_CHANGED) grant çapasını günceller.
 *
 * Neden: çapa normalde yalnızca uygulama açıkken güncellenir. Uygulama kapalıyken uzun süre geçip
 * saat geri alınır ve telefon yeniden başlatılırsa, monoton saat sıfırlandığı için aradaki süre
 * görülemezdi. Saat değiştiği anda telefon henüz yeniden başlamamıştır; monoton saat geçerlidir ve
 * harcanan süre çapaya yazılır, yeniden başlatmadan sonra da korunur.
 *
 * ACTION_TIME_CHANGED, Android 8+ örtük yayın kısıtlamasından muaftır (manifest alıcısı uygulama
 * kapalıyken de çalışır). Yayını yalnızca sistem gönderebilir.
 */
class ClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED) return
        val app = context.applicationContext as? ConnectApplication ?: return
        val pending = goAsync()
        app.container.recordGrantTime { pending.finish() }
    }
}
