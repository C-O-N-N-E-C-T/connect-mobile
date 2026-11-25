package com.connect.mobile.auth.grant

import com.connect.mobile.auth.LoginUseCase
import com.connect.mobile.data.session.StoredGrant
import java.time.Duration
import java.time.Instant

/**
 * Çevrimdışı görev penceresi. Ekip tasarım parametresidir (SoW'un dikte ettiği sabit bir sayı değil);
 * FR-R-15 kesintisiz saha görevini desteklemek için seçildi. [OfflineGrantPolicy] kurucusundan değişir.
 */
const val DEFAULT_OFFLINE_GRANT_HOURS: Long = 72

/**
 * Uygulamanın yetenekleri.
 * - [requiresGrant]: geçerli bir Offline Mission Grant gerekir mi.
 * - [lockedOnExpiry]: grant penceresi dolunca kilitlenir mi (yalnızca PII ve bulut senkronu).
 * - [biometricGuarded]: yeniden başlatma / 15 dk arka plan sonrası biyometri ister mi.
 */
enum class Capability(
    val requiresGrant: Boolean,
    val lockedOnExpiry: Boolean,
    val biometricGuarded: Boolean,
) {
    VICTIM_MODE(requiresGrant = false, lockedOnExpiry = false, biometricGuarded = false),
    RELAY_MODE(requiresGrant = false, lockedOnExpiry = false, biometricGuarded = false),
    PASSIVE_SCAN(requiresGrant = false, lockedOnExpiry = false, biometricGuarded = false),
    RESCUER_MODE(requiresGrant = true, lockedOnExpiry = false, biometricGuarded = true),
    VICTIM_PII_DETAIL(requiresGrant = true, lockedOnExpiry = true, biometricGuarded = true),
    CLOUD_SYNC(requiresGrant = true, lockedOnExpiry = true, biometricGuarded = false),
}

sealed interface GrantStatus {
    data object Missing : GrantStatus

    data class Mismatch(val reason: Reason) : GrantStatus {
        enum class Reason { DEVICE, ROLE }
    }

    /** [remainingMillis]: duvar saati ve monoton pencereden kısa olanına göre kalan süre. */
    data class Valid(val remainingMillis: Long) : GrantStatus

    data class Expired(val reason: Reason) : GrantStatus {
        /** [isFinal]: kendiliğinden düzelemez (yalnızca yeniden çevrimiçi giriş açar). */
        enum class Reason(val isFinal: Boolean) {
            WALL_CLOCK(isFinal = true),
            MONOTONIC_WINDOW(isFinal = true),
            CLOCK_ROLLBACK(isFinal = false),
            STATE_UNAVAILABLE(isFinal = false),
        }
    }
}

sealed interface Access {
    data object Allowed : Access
    /** Grant yok: çevrimiçi Field Rescuer girişi gerekir. */
    data object LoginRequired : Access
    /** Grant bu cihaza veya kurtarıcı rolüne ait değil. */
    data object Denied : Access
    /** Pencere doldu: "Çevrimiçi Yeniden Doğrulama Gerekli". Veri silinmez. */
    data object ReauthRequired : Access
}

/** [anchor]: kaydedilmesi gereken güncel çapa (grant yoksa, uyuşmuyorsa veya saat geri alındıysa null). */
data class GrantEvaluation(val status: GrantStatus, val anchor: GrantAnchor?)

/**
 * OSP-73 kapı görevlisi. Saf Kotlin, Android'e bağımlı değil; HİÇBİR ŞEY SİLMEZ, yalnızca karar verir.
 *
 * Token'lara bakmaz: 1 saatlik access_token dolsa da grant penceresi boyunca çalışılır; refresh_token
 * tek başına hiçbir çevrimdışı yetki vermez (yalnızca grant karar verir).
 */
class OfflineGrantPolicy(offlineGrantHours: Long = DEFAULT_OFFLINE_GRANT_HOURS) {
    init {
        require(offlineGrantHours > 0) { "offlineGrantHours must be > 0" }
    }

    val windowMillis: Long = Duration.ofHours(offlineGrantHours).toMillis()

    fun evaluate(
        grant: StoredGrant?,
        ourDeviceId: String,
        anchor: GrantAnchor?,
        now: Instant,
        elapsedRealtimeMillis: Long,
        bootCount: Int,
    ): GrantEvaluation {
        if (grant == null) return GrantEvaluation(GrantStatus.Missing, anchor = null)
        if (grant.deviceId != ourDeviceId) {
            return GrantEvaluation(GrantStatus.Mismatch(GrantStatus.Mismatch.Reason.DEVICE), anchor = null)
        }
        if (grant.role != LoginUseCase.RESCUER_ROLE) {
            return GrantEvaluation(GrantStatus.Mismatch(GrantStatus.Mismatch.Reason.ROLE), anchor = null)
        }

        val nowMs = now.toEpochMilli()
        val authMs = grant.authenticatedAt.toEpochMilli()
        val expiresMs = grant.expiresAt.toEpochMilli()
        val own = anchor?.takeIf { it.belongsTo(grant) }
        val wallConsumed = nowMs - authMs
        val sameBoot = own != null &&
            own.bootCount == bootCount &&
            elapsedRealtimeMillis >= own.elapsedAtAnchorMillis

        // Duvar saatine göre harcanan süre her zaman alt sınırdır: saati ileri almak yalnızca pencereyi
        // kısaltır. Böylece BOOT_COUNT okunamadığında (UNKNOWN_BOOT) fark edilmeyen bir yeniden
        // başlatma da kapalı geçen süreyi kaybettirmez.
        val consumed = when {
            own == null -> wallConsumed // ilk kontrol (ör. login hemen sonrası): sunucu zamanından başla
            sameBoot -> maxOf(own.consumedAtAnchorMillis + (elapsedRealtimeMillis - own.elapsedAtAnchorMillis), wallConsumed)
            else -> maxOf(own.consumedAtAnchorMillis, wallConsumed) // yeniden başlatma: kapalı süre duvar saatiyle
        }.coerceAtLeast(0L)

        val lastSeen = own?.lastSeenWallMillis ?: nowMs
        val next = GrantAnchor(
            grantKey = GrantAnchor.keyOf(grant),
            bootCount = bootCount,
            elapsedAtAnchorMillis = elapsedRealtimeMillis,
            consumedAtAnchorMillis = consumed,
            lastSeenWallMillis = maxOf(lastSeen, nowMs), // asla geriye gitmez
        )

        val skew = LoginUseCase.MAX_CLOCK_SKEW.toMillis()
        val status = when {
            nowMs < authMs - skew || nowMs < lastSeen - skew ->
                GrantStatus.Expired(GrantStatus.Expired.Reason.CLOCK_ROLLBACK)
            nowMs >= expiresMs -> GrantStatus.Expired(GrantStatus.Expired.Reason.WALL_CLOCK)
            consumed >= windowMillis -> GrantStatus.Expired(GrantStatus.Expired.Reason.MONOTONIC_WINDOW)
            else -> GrantStatus.Valid(minOf(windowMillis - consumed, expiresMs - nowMs))
        }
        // Geri alınmış saatle hesaplanan çapa kaydedilmez: eski çapa (açılış sayacı, harcanan süre,
        // son görülen zaman) korunur, saat düzelince kapalı geçen süre de doğru sayılır.
        val rolledBack = status is GrantStatus.Expired && status.reason == GrantStatus.Expired.Reason.CLOCK_ROLLBACK
        return GrantEvaluation(status, if (rolledBack) null else next)
    }

    fun access(capability: Capability, status: GrantStatus): Access = when {
        !capability.requiresGrant -> Access.Allowed // AC-01: Kurban, Röle, pasif tarama
        status is GrantStatus.Missing -> Access.LoginRequired
        status is GrantStatus.Mismatch -> Access.Denied
        status is GrantStatus.Expired -> if (capability.lockedOnExpiry) Access.ReauthRequired else Access.Allowed
        else -> Access.Allowed
    }
}
