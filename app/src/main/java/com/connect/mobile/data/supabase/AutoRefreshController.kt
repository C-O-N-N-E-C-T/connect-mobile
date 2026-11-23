package com.connect.mobile.data.supabase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** Auto-refresh'i açılıp kapatılabilen taraf (gerçekte [SupabaseKtAuthGateway]). */
interface AutoRefreshTarget {
    /** Bellekte veya depoda yenilenebilir bir oturum var mı (Authenticated ya da RefreshFailure). */
    val hasSession: Flow<Boolean>

    /** Yenilemeyi başlatır/sürdürür; oturum yoksa hiçbir şey yapmaz. Hata yutulur. */
    suspend fun resumeAutoRefresh()

    /** Yenilemeyi durdurur; oturuma ve depoya dokunmaz. */
    fun pauseAutoRefresh()
}

/**
 * supabase-kt token yenilemesinin durum makinesi (OSP-75, Jira madde 3).
 *
 * Kural: çevrimiçi VE uygulama ön planda VE oturum varsa yenileme çalışır, aksi halde durur.
 * supabase-kt'nin kendi yaşam döngüsü geri çağrıları (`enableLifecycleCallbacks`) kapalıdır; onlar
 * ağı bilmez ve çevrimdışıyken ön plana gelindiğinde ~10 sn'de bir başarısız yenileme döngüsü başlatır.
 *
 * Yenilemeyi durdurmak oturumu SİLMEZ: 1 saatten uzun uçak modunda erişim token'ı bellekte/diskte
 * süresi geçmiş olarak kalır, grant (72 sa) zaten ayrı tutulur; ağ dönünce yenileme kaldığı yerden
 * devam eder. Hedef (gateway) tembel oluşturulduğu için sonradan [attach] ile bağlanır.
 */
class AutoRefreshController(
    private val online: StateFlow<Boolean>,
    private val foreground: StateFlow<Boolean>,
) {
    private val target = MutableStateFlow<AutoRefreshTarget?>(null)

    /** Gateway, ilk oturum yüklemesinde yenilemenin hemen açılıp açılmayacağını buradan sorar. */
    fun isRefreshAllowedNow(): Boolean = online.value && foreground.value

    fun attach(target: AutoRefreshTarget) {
        this.target.value = target
    }

    /** Süreç ömrü boyunca bir kez başlatılır; [scope] iptal edilince durur. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope): Job = scope.launch {
        target.filterNotNull()
            .flatMapLatest { t ->
                combine(online, foreground, t.hasSession) { isOnline, isForeground, hasSession ->
                    // O3: hasSession da anahtarın parçası: oturum false->true geçtiğinde (ör. yükleme
                    // bitti) karar "durdur" olsa bile yeniden uygulanır; yükleme sırasında başlamış
                    // olabilecek bir yenileme böylece kesin durdurulur.
                    Decision(t, run = isOnline && isForeground && hasSession, hasSession = hasSession)
                }
            }
            .distinctUntilChanged()
            // collectLatest: durum değişince (ör. çevrimdışı oldu) yarım kalan bir başlatma iptal edilir
            // ve hemen durdurma uygulanır; işlemler üst üste binmez.
            .collectLatest { d ->
                try {
                    if (d.run) d.target.resumeAutoRefresh() else d.target.pauseAutoRefresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Denetleyici hiçbir hatayla ölmemeli; bir sonraki durum değişikliği yeniden dener.
                }
            }
    }

    private data class Decision(val target: AutoRefreshTarget, val run: Boolean, val hasSession: Boolean)
}
