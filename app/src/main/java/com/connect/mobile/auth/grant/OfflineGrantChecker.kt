package com.connect.mobile.auth.grant

import com.connect.mobile.data.device.DeviceIdProvider
import com.connect.mobile.data.session.StoredGrant
import kotlinx.coroutines.CancellationException
import java.time.Clock

/**
 * Policy'yi gerçek saatlere ve çapa deposuna bağlar (OSP-73). Ekranlar (Kurtarıcı, ileride PII ve
 * senkron) yalnızca bunu kullanır: [check] ile durum, [access] ile yetenek kararı.
 */
class OfflineGrantChecker(
    private val policy: OfflineGrantPolicy,
    private val anchors: GrantAnchorStore,
    private val deviceIdProvider: DeviceIdProvider,
    private val wallClock: Clock,
    private val monotonic: MonotonicClock,
) {
    suspend fun check(grant: StoredGrant?): GrantStatus {
        val anchor = try {
            anchors.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Çapa okunamıyor (ör. Keystore geçici hatası): fail-closed, bir sonraki kontrol yeniden dener.
            return GrantStatus.Expired(GrantStatus.Expired.Reason.STATE_UNAVAILABLE)
        }
        val evaluation = policy.evaluate(
            grant = grant,
            ourDeviceId = deviceIdProvider.deviceId(),
            anchor = anchor,
            now = wallClock.instant(),
            elapsedRealtimeMillis = monotonic.elapsedRealtimeMillis(),
            bootCount = monotonic.bootCount(),
        )
        evaluation.anchor?.let { next ->
            try {
                anchors.write(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Yazılamazsa karar yine geçerli; bir sonraki kontrol yeniden yazar.
            }
        }
        return evaluation.status
    }

    fun access(capability: Capability, status: GrantStatus): Access = policy.access(capability, status)
}
