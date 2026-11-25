package com.connect.mobile.auth.grant

import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.Duration

/** Elle ilerletilen monoton saat; [reboot] telefonun yeniden başlatılmasını taklit eder. */
class FakeMonotonicClock(
    var elapsed: Long = 3_600_000L,
    var boot: Int = 1,
) : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = elapsed
    override fun bootCount(): Int = boot

    fun advance(d: Duration) {
        elapsed += d.toMillis()
    }

    /** Sayaç artar, açılıştan beri geçen süre sıfıra yakın başlar. */
    fun reboot(uptimeAfterBootMillis: Long = 60_000L) {
        boot++
        elapsed = uptimeAfterBootMillis
    }
}

/** ViewModel testleri için: test zamanlayıcısının sanal zamanıyla birlikte ilerler. */
class SchedulerMonotonicClock(
    private val scheduler: TestCoroutineScheduler,
    private val base: Long = 3_600_000L,
    private val boot: Int = 1,
) : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = base + scheduler.currentTime
    override fun bootCount(): Int = boot
}
