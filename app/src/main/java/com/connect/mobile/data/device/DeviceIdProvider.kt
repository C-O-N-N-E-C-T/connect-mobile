package com.connect.mobile.data.device

import com.connect.mobile.data.session.SessionStore
import java.util.UUID

/**
 * Install-scoped device identity: a random v4 UUID created on first launch. IMEI and ANDROID_ID are
 * never used. It survives logout, not uninstall (backup is disabled).
 */
class DeviceIdProvider(
    private val store: SessionStore,
    private val rawDeviceName: () -> String,
) {
    suspend fun deviceId(): String = store.getOrCreateDeviceId { UUID.randomUUID().toString() }

    fun deviceName(): String = rawDeviceName().trim().take(MAX_NAME_LENGTH)

    companion object {
        const val MAX_NAME_LENGTH = 100
    }
}
