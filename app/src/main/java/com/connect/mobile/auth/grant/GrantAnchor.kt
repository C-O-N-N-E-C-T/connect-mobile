package com.connect.mobile.auth.grant

import com.connect.mobile.data.session.StoredGrant

/**
 * Grant penceresinden ne kadar harcandığının monoton saatteki kaydı (OSP-73).
 *
 * - Aynı açılışta: harcanan = [consumedAtAnchorMillis] + (elapsedRealtime - [elapsedAtAnchorMillis]).
 *   Duvar saati geri alınsa bile azalmaz.
 * - Yeniden başlatmadan sonra monoton saat sıfırlanır: kapalı geçen süre duvar saatiyle sayılır,
 *   [lastSeenWallMillis] geriye gidişi yakalar.
 * - [grantKey] çapayı tek bir grant'e bağlar; eski grant'in çapası yeni grant'e uygulanmaz.
 */
data class GrantAnchor(
    val grantKey: String,
    val bootCount: Int,
    val elapsedAtAnchorMillis: Long,
    val consumedAtAnchorMillis: Long,
    val lastSeenWallMillis: Long,
) {
    fun belongsTo(grant: StoredGrant): Boolean = grantKey == keyOf(grant)

    companion object {
        fun keyOf(grant: StoredGrant): String =
            "${grant.userId}|${grant.deviceId}|${grant.authenticatedAt.toEpochMilli()}|${grant.expiresAt.toEpochMilli()}"
    }
}

interface GrantAnchorStore {
    suspend fun read(): GrantAnchor?
    suspend fun write(anchor: GrantAnchor)
    suspend fun clear()
}
