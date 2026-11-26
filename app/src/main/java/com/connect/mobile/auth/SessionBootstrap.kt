package com.connect.mobile.auth

import com.connect.mobile.data.device.DeviceIdProvider
import com.connect.mobile.data.session.SessionStore
import com.connect.mobile.data.session.StoredGrant
import kotlinx.coroutines.CancellationException

sealed interface BootstrapResult {
    data class MissionActive(val grant: StoredGrant) : BootstrapResult
    data object LoginRequired : BootstrapResult
}

/**
 * Start-up decision. A Supabase session WITHOUT a grant is an interrupted or rejected login: wipe it
 * locally (no remote calls). Store read errors propagate; the ViewModel shows the generic error.
 * An expired grant still gives MissionActive: the ViewModel shows it as expired.
 *
 * The OSP-73 OfflineGrantPolicy check and the biometric lock run in RescuerAuthViewModel / RescuerAuthRoute.
 */
class SessionBootstrap(
    private val store: SessionStore,
    private val deviceIdProvider: DeviceIdProvider,
    private val logout: LogoutUseCase,
) {
    suspend operator fun invoke(): BootstrapResult {
        // The install device_id exists from the first launch (design 3.3). Best effort: on a full disk
        // the write fails while reads still work, and a stored grant must still reach the mission screen.
        try {
            deviceIdProvider.deviceId()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // login retries get-or-create
        }
        store.readGrant()?.let { return BootstrapResult.MissionActive(it) }
        if (store.readSupabaseSession() != null) logout(remote = false)
        return BootstrapResult.LoginRequired
    }
}
