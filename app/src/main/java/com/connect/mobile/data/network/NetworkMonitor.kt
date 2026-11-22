package com.connect.mobile.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.connect.mobile.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Varsayılan ağın internet yeteneği olup olmadığını izler (OSP-75: çevrimdışıyken supabase-kt token
 * yenilemesi askıya alınır).
 *
 * "Çevrimiçi" = varsayılan ağda NET_CAPABILITY_INTERNET var. Bilerek VALIDATED aranmaz: sahada
 * (afet bölgesi) Android'in internet doğrulamasını geçemeyen ama backend'e ulaşabilen yerel ağlar
 * olabilir; orada da yenileme denenmeli. Uçak modunda/ağ yokken varsayılan ağ kaybolur -> çevrimdışı.
 */
class NetworkMonitor(context: Context, private val log: AppLogger = AppLogger.NONE) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    // D6: son bilinen varsayılan ağ. Ağ geçişinde (Wi-Fi -> mobil) eski ağın onLost'u, yeni ağın
    // onCapabilitiesChanged'inden SONRA gelebilir; o durumda çevrimdışı işaretlenmemeli.
    @Volatile
    private var defaultNetwork: Network? = try {
        connectivity?.activeNetwork
    } catch (e: RuntimeException) {
        null
    }
    private val _isOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            defaultNetwork = network
            _isOnline.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        override fun onLost(network: Network) {
            // Yalnızca bilinen varsayılan ağ kaybolduysa çevrimdışı; yeni varsayılan ağ gelirse
            // onCapabilitiesChanged tekrar çağrılır.
            val current = defaultNetwork
            if (current == null || current == network) {
                defaultNetwork = null
                _isOnline.value = false
            }
        }
    }

    /** Süreç ömrü boyunca bir kez çağrılır (AppContainer). */
    fun start() {
        try {
            connectivity?.registerDefaultNetworkCallback(callback)
        } catch (e: RuntimeException) {
            // SecurityException / çok fazla callback: izleme yoksa eski davranışa (her zaman çevrimiçi
            // varsay) dönülür; yenileme denenir ve en kötü ihtimalle ağ hatasıyla başarısız olur.
            log.debug("network monitor unavailable: ${e.javaClass.simpleName}")
            _isOnline.value = true
        }
    }

    private fun currentlyOnline(): Boolean {
        val cm = connectivity ?: return true
        return try {
            val caps = cm.getNetworkCapabilities(defaultNetwork ?: cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: RuntimeException) {
            true
        }
    }
}
