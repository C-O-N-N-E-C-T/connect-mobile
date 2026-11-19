package com.connect.mobile.auth

import com.connect.mobile.data.session.SessionStore
import com.connect.mobile.data.session.StoredGrant

sealed interface BootstrapResult {
    data class MissionActive(val grant: StoredGrant) : BootstrapResult
    data object LoginRequired : BootstrapResult
}

/**
 * Start-up decision. A Supabase session WITHOUT a grant is an interrupted or rejected login: wipe it
 * locally (no remote calls).
 *
 * TODO(OSP-73): OfflineGrantPolicy gate (expiry, monotonic clock) + BiometricPrompt before MissionActive.
 */
class SessionBootstrap(
    private val store: SessionStore,
    private val logout: LogoutUseCase,
) {
    suspend operator fun invoke(): BootstrapResult {
        store.readGrant()?.let { return BootstrapResult.MissionActive(it) }
        if (store.readSupabaseSession() != null) logout(remote = false)
        return BootstrapResult.LoginRequired
    }
}
