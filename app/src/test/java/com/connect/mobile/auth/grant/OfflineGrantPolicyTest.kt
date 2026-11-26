package com.connect.mobile.auth.grant

import com.connect.mobile.auth.grant.Capability.CLOUD_SYNC
import com.connect.mobile.auth.grant.Capability.PASSIVE_SCAN
import com.connect.mobile.auth.grant.Capability.RELAY_MODE
import com.connect.mobile.auth.grant.Capability.RESCUER_MODE
import com.connect.mobile.auth.grant.Capability.VICTIM_MODE
import com.connect.mobile.auth.grant.Capability.VICTIM_PII_DETAIL
import com.connect.mobile.auth.grant.GrantStatus.Expired.Reason.CLOCK_ROLLBACK
import com.connect.mobile.auth.grant.GrantStatus.Expired.Reason.MONOTONIC_WINDOW
import com.connect.mobile.auth.grant.GrantStatus.Expired.Reason.WALL_CLOCK
import com.connect.mobile.data.session.StoredGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class OfflineGrantPolicyTest {
    private val auth = Instant.parse("2026-11-20T08:00:00Z")
    private val device = "device-1"
    private val grant = StoredGrant("u1", device, "rescuer", auth, auth.plus(Duration.ofHours(72)))
    private val policy = OfflineGrantPolicy()
    private val mono = FakeMonotonicClock()
    private var now = auth
    private var anchor: GrantAnchor? = null
    /** BootReceiver'ın açılışta kaydettiği (açılış sayacı, duvar saati); yoksa null. */
    private var bootRecord: BootWallRecord? = null

    /** BootReceiver gibi: bu açılış için o anki duvar saatini kaydeder. */
    private fun recordBoot(at: Instant = now) {
        bootRecord = BootWallRecord(mono.bootCount(), at.toEpochMilli())
    }

    /** Uygulamadaki gibi: her kontrol çapayı günceller. */
    private fun check(
        g: StoredGrant? = grant,
        deviceId: String = device,
        p: OfflineGrantPolicy = policy,
    ): GrantStatus {
        val e = p.evaluate(g, deviceId, anchor, now, mono.elapsedRealtimeMillis(), mono.bootCount(), bootRecord)
        e.anchor?.let { anchor = it }
        return e.status
    }

    /** Gerçek zaman geçer: duvar saati ve monoton saat birlikte ilerler. */
    private fun pass(d: Duration) {
        now = now.plus(d)
        mono.advance(d)
    }

    private fun hours(h: Long) = Duration.ofHours(h)

    /** AC-01 / S1, S11 */
    @Test fun victimRelayAndScanNeedNoGrant() {
        val statuses = listOf(
            GrantStatus.Missing,
            GrantStatus.Mismatch(GrantStatus.Mismatch.Reason.DEVICE),
            GrantStatus.Expired(WALL_CLOCK),
            GrantStatus.Valid(1),
        )
        for (s in statuses) for (c in listOf(VICTIM_MODE, RELAY_MODE, PASSIVE_SCAN)) {
            assertEquals("$c / $s", Access.Allowed, policy.access(c, s))
        }
    }

    /** S1 */
    @Test fun noGrantRequiresLogin() {
        val s = check(g = null)
        assertEquals(GrantStatus.Missing, s)
        assertEquals(Access.LoginRequired, policy.access(RESCUER_MODE, s))
        assertEquals(Access.LoginRequired, policy.access(VICTIM_PII_DETAIL, s))
    }

    /** S7 */
    @Test fun grantForAnotherDeviceIsDenied() {
        val s = check(deviceId = "other-device")
        assertEquals(GrantStatus.Mismatch(GrantStatus.Mismatch.Reason.DEVICE), s)
        assertEquals(Access.Denied, policy.access(RESCUER_MODE, s))
        assertEquals(Access.Denied, policy.access(VICTIM_PII_DETAIL, s))
    }

    /** S8 */
    @Test fun nonRescuerRoleIsDenied() {
        val s = check(g = grant.copy(role = "coordinator"))
        assertEquals(GrantStatus.Mismatch(GrantStatus.Mismatch.Reason.ROLE), s)
        assertEquals(Access.Denied, policy.access(RESCUER_MODE, s))
    }

    /** S2, S3: 1 saatlik access_token'ın dolması policy'yi etkilemez (token girdisi yok). */
    @Test fun rescuerModeWorksAfterAccessTokenExpiry() {
        check()
        pass(hours(2))
        val s = check()
        assertTrue(s is GrantStatus.Valid)
        for (c in Capability.entries) assertEquals("$c", Access.Allowed, policy.access(c, s))
    }

    @Test fun validJustBeforeWindowExpiredAtWindow() {
        check()
        pass(hours(72).minusMillis(1))
        assertEquals(GrantStatus.Valid(1), check())
        pass(Duration.ofMillis(1))
        assertTrue(check() is GrantStatus.Expired)
    }

    /** S4: süre dolunca yalnızca PII ve senkron kilitlenir. */
    @Test fun expiryLocksOnlyPiiAndSync() {
        check()
        pass(hours(73))
        val s = check()
        assertTrue(s is GrantStatus.Expired)
        assertEquals(Access.Allowed, policy.access(RESCUER_MODE, s))
        assertEquals(Access.Allowed, policy.access(VICTIM_MODE, s))
        assertEquals(Access.Allowed, policy.access(RELAY_MODE, s))
        assertEquals(Access.Allowed, policy.access(PASSIVE_SCAN, s))
        assertEquals(Access.ReauthRequired, policy.access(VICTIM_PII_DETAIL, s))
        assertEquals(Access.ReauthRequired, policy.access(CLOUD_SYNC, s))
    }

    /** S5: aynı açılışta duvar saati geri alınsa da monoton saat gerçek süreyi bilir. */
    @Test fun clockRollbackDoesNotExtendWindow() {
        check()
        mono.advance(hours(73))
        now = auth.plus(hours(1)) // kullanıcı saati geri aldı
        assertEquals(GrantStatus.Expired(MONOTONIC_WINDOW), check())
    }

    @Test fun configurableWindow() {
        val short = OfflineGrantPolicy(offlineGrantHours = 24)
        check(p = short)
        pass(hours(23))
        assertTrue(check(p = short) is GrantStatus.Valid)
        pass(hours(1))
        assertEquals(GrantStatus.Expired(MONOTONIC_WINDOW), check(p = short))
    }

    /** S6: yeniden başlatmada kapalı geçen süre duvar saatiyle sayılır. */
    @Test fun rebootCountsOffTimeByWallClock() {
        check()
        pass(hours(10))
        check()
        mono.reboot()
        now = auth.plus(hours(30))
        assertEquals(GrantStatus.Valid(hours(42).toMillis()), check())
    }

    /** S6: yeniden başlatma + saati geri alma. */
    @Test fun rebootWithRolledBackClockExpires() {
        check()
        pass(hours(10))
        check()
        mono.reboot()
        now = auth.plus(hours(2))
        assertEquals(GrantStatus.Expired(CLOCK_ROLLBACK), check())
    }

    /** Geri alınmış saatte harcanan süre ve son görülen zaman korunur; saat düzelince kapalı süre de sayılır. */
    @Test fun rolledBackClockAfterRebootDoesNotLoseOffTime() {
        val short = OfflineGrantPolicy(offlineGrantHours = 24)
        check(p = short)
        pass(hours(10))
        check(p = short)
        val before = anchor
        mono.reboot()
        now = auth.plus(hours(2)) // açılışta saat sıfırlanmış / geri alınmış
        assertEquals(GrantStatus.Expired(CLOCK_ROLLBACK), check(p = short))
        assertEquals(before!!.consumedAtAnchorMillis, anchor!!.consumedAtAnchorMillis)
        assertEquals(before.lastSeenWallMillis, anchor!!.lastSeenWallMillis)
        mono.advance(Duration.ofMinutes(1))
        now = auth.plus(hours(30)) // NTP saati düzeltti: telefon 20 saat kapalı kalmış
        assertEquals(GrantStatus.Expired(MONOTONIC_WINDOW), check(p = short))
    }

    /** BOOT_COUNT okunamazsa yeniden başlatma fark edilmeyebilir; duvar saati alt sınır olarak kalır. */
    @Test fun unknownBootCountStillCountsOffTime() {
        val short = OfflineGrantPolicy(offlineGrantHours = 24)
        mono.boot = MonotonicClock.UNKNOWN_BOOT
        check(p = short)
        pass(hours(10))
        check(p = short)
        // Yeniden başlatma: sayaç yine UNKNOWN, açılıştan beri geçen süre çapadakinden büyük.
        mono.elapsed = anchor!!.elapsedAtAnchorMillis + hours(1).toMillis()
        now = auth.plus(hours(30))
        assertEquals(GrantStatus.Expired(MONOTONIC_WINDOW), check(p = short))
    }

    @Test fun clockBeforeAuthenticatedAtIsRollback() {
        now = auth.minus(Duration.ofMinutes(10))
        assertEquals(GrantStatus.Expired(CLOCK_ROLLBACK), check())
        assertNull(anchor) // çapasızken geri alınmış saat son görülen zaman olarak yazılmaz
    }

    /**
     * Review senaryosu: uygulama kapalıyken 70 sa geçer, saat geri alınır, telefon yeniden başlatılır.
     * Saat değişikliği yayını (ClockChangeReceiver) aynı açılışta kontrol çalıştırır; monoton saat 70
     * saati görür ve çapaya yazar. Yeniden başlatmadan sonra pencere yeniden açılmaz.
     */
    @Test fun clockChangeCheckKeepsUptimeAcrossReboot() {
        check()
        mono.advance(hours(70)) // uygulama kapalı, kontrol yok
        now = auth // saat "son görülen" zamana geri alındı -> ACTION_TIME_CHANGED -> check()
        assertEquals(GrantStatus.Valid(hours(2).toMillis()), check())
        mono.reboot()
        now = auth.plus(hours(1))
        assertEquals(GrantStatus.Valid(hours(2).toMillis()), check())
        pass(hours(2))
        assertEquals(GrantStatus.Expired(MONOTONIC_WINDOW), check())
    }

    /** Saat çok geri alınınca (tespit edilir) de harcanan süre kaydedilir; sonra saati düzeltmek işe yaramaz. */
    @Test fun deepRollbackStillRecordsConsumedTime() {
        check()
        mono.advance(hours(70))
        now = auth.minus(Duration.ofDays(1))
        assertEquals(GrantStatus.Expired(CLOCK_ROLLBACK), check())
        assertEquals(hours(70).toMillis(), anchor!!.consumedAtAnchorMillis)
        assertEquals(auth.toEpochMilli(), anchor!!.lastSeenWallMillis)
        mono.reboot()
        now = auth // yeniden başlatma sonrası saat son görülen zamana getirildi
        assertEquals(GrantStatus.Valid(hours(2).toMillis()), check())
    }

    @Test fun smallBackwardCorrectionWithinSkewIsTolerated() {
        check()
        pass(hours(10))
        check()
        now = now.minus(Duration.ofMinutes(1)) // NTP düzeltmesi
        assertTrue(check() is GrantStatus.Valid)
        assertEquals(auth.plus(hours(10)).toEpochMilli(), anchor!!.lastSeenWallMillis)
    }

    /** OSP-74'ten kalan, çapası olmayan grant: duvar saatiyle başlar. */
    @Test fun missingAnchorFallsBackToWallClock() {
        now = auth.plus(hours(5))
        assertEquals(GrantStatus.Valid(hours(67).toMillis()), check())
        assertEquals(hours(5).toMillis(), anchor!!.consumedAtAnchorMillis)
    }

    @Test fun anchorOfAnotherGrantIsIgnored() {
        check()
        pass(hours(50))
        check()
        val fresh = grant.copy(authenticatedAt = now, expiresAt = now.plus(hours(72)))
        assertEquals(GrantStatus.Valid(hours(72).toMillis()), check(g = fresh))
    }

    @Test fun windowMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { OfflineGrantPolicy(offlineGrantHours = 0) }
    }

    /**
     * Review sırası: uygulama kapalı 70 sa -> ÖNCE yeniden başlat -> SONRA saati geri al. Monoton saat
     * sıfırlandı; BootReceiver açılışta (LOCKED_BOOT_COMPLETED, saat henüz doğru) duvar saatini kaydetti.
     */
    @Test fun rebootThenRollbackIsCaughtByBootFloor() {
        check()
        pass(hours(70)) // uygulama kapalı, kontrol yok
        mono.reboot()
        recordBoot() // açılış kaydı (saat henüz doğru)
        val bootWall = now
        now = auth // kilit açıldıktan sonra saat geri alındı
        assertEquals(GrantStatus.Expired(CLOCK_ROLLBACK), check())
        assertEquals(hours(70).toMillis(), anchor!!.consumedAtAnchorMillis)
        // Saat açılış kaydının 5 dk altına bile çekilmezse: 70 sa harcanmış sayılır, pencere yeniden açılmaz.
        now = bootWall
        assertEquals(GrantStatus.Valid(hours(2).toMillis()), check())
    }

    /** BOOT_COMPLETED kontrolü doğru saatle çalıştıysa sonraki geri alma son görülen zamandan yakalanır. */
    @Test fun bootCheckThenRollbackIsCaught() {
        check()
        pass(hours(70))
        mono.reboot()
        assertEquals(GrantStatus.Valid(hours(2).toMillis()), check()) // BOOT_COMPLETED -> check()
        now = auth
        assertEquals(GrantStatus.Expired(CLOCK_ROLLBACK), check())
    }

    /** Girişten önceki bir açılış kaydı pencereyi etkilemez. */
    @Test fun bootFloorBeforeLoginHasNoEffect() {
        recordBoot(at = auth.minus(hours(5)))
        check()
        pass(hours(1))
        assertEquals(GrantStatus.Valid(hours(71).toMillis()), check())
    }

    /**
     * BİLİNEN SINIR (tasarım notu §8): uygulama "Zorla durdur" yapılmışsa sistem açılış/saat yayınlarını
     * göndermez; açılış kaydı olmadan "70 sa -> yeniden başlat -> saati geri al" sırası görülemez.
     * Bu test sınırı belgeler; davranış değişirse (ör. GNSS zamanı eklenirse) güncellenmeli.
     */
    @Test fun knownLimitWithoutBootSignalUptimeIsLost() {
        check()
        pass(hours(70))
        mono.reboot()
        now = auth.plus(Duration.ofMinutes(1)) // saat son görülen zamana geri alındı, kayıt yok
        assertTrue((check() as GrantStatus.Valid).remainingMillis > hours(70).toMillis())
    }

    /**
     * Review: telefon yanlışlıkla ileri tarihle (ör. 1 yıl sonra) açıldı. Eski grant bu açılışta geri alma
     * sayılır (fail-closed), ama YENİ giriş kaydı yok sayar ve Valid olur; sonraki açılışta kayıt taşınmaz.
     */
    @Test fun futureBootRecordThenNewLoginIsValid() {
        check()
        pass(hours(1))
        mono.reboot()
        recordBoot(at = now.plus(Duration.ofDays(365))) // donanım saati ileri tarihteydi
        assertEquals(GrantStatus.Expired(CLOCK_ROLLBACK), check()) // eski grant: geri alma gibi görünür

        // Yeni çevrimiçi giriş (sunucu zamanı = gerçek zaman), aynı açılış, kayıt hâlâ ileri tarihli.
        val newAuth = now
        val fresh = StoredGrant("u1", device, "rescuer", newAuth, newAuth.plus(hours(72)))
        assertEquals(GrantStatus.Valid(hours(72).toMillis()), check(g = fresh))
        pass(hours(1))
        assertEquals(GrantStatus.Valid(hours(71).toMillis()), check(g = fresh))

        // Sonraki açılış: kayıt yazılmadan önce (ör. yayın gecikti) eski açılışın kaydı kullanılmaz.
        pass(hours(1))
        mono.reboot()
        assertEquals(GrantStatus.Valid(hours(70).toMillis()), check(g = fresh))
    }

    /** Önceki açılışın kaydı (sayacı tutmuyor) yeniden başlatmadan sonra kullanılmaz. */
    @Test fun bootRecordFromPreviousBootIsIgnored() {
        check()
        recordBoot(at = auth.plus(Duration.ofDays(365))) // açılış 1'in (yanlış) kaydı
        pass(hours(1))
        check()
        mono.reboot() // açılış 2, bu açılış için kayıt yok
        assertEquals(GrantStatus.Valid(hours(71).toMillis()), check())
        pass(hours(1))
        assertEquals(GrantStatus.Valid(hours(70).toMillis()), check())
    }

    /** Açılış sayacı okunamıyorsa kayıt bir açılışa bağlanamaz, kullanılmaz. */
    @Test fun bootRecordIgnoredWhenBootCountUnknown() {
        mono.boot = MonotonicClock.UNKNOWN_BOOT
        check()
        pass(hours(1))
        bootRecord = BootWallRecord(MonotonicClock.UNKNOWN_BOOT, now.plus(Duration.ofDays(365)).toEpochMilli())
        assertEquals(GrantStatus.Valid(hours(71).toMillis()), check())
    }
}
