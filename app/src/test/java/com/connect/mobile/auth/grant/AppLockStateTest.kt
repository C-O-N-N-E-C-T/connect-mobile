package com.connect.mobile.auth.grant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class AppLockStateTest {
    private val mono = FakeMonotonicClock()
    private val lock = AppLockState(mono)

    private fun backgroundFor(d: Duration) {
        lock.onBackground()
        mono.advance(d)
        lock.onForeground()
    }

    /** S10: yeniden başlatma = yeni süreç = kilitli. */
    @Test fun startsLocked() {
        assertTrue(lock.locked.value)
        assertTrue(AppLockState(mono).locked.value)
    }

    @Test fun unlockOpens() {
        lock.unlock()
        assertFalse(lock.locked.value)
    }

    /** S9 */
    @Test fun tenMinutesInBackgroundStaysUnlocked() {
        lock.unlock()
        backgroundFor(Duration.ofMinutes(10))
        assertFalse(lock.locked.value)
    }

    @Test fun exactlyFifteenMinutesStaysUnlocked() {
        lock.unlock()
        backgroundFor(Duration.ofMinutes(15))
        assertFalse(lock.locked.value)
    }

    /** S10 */
    @Test fun moreThanFifteenMinutesLocks() {
        lock.unlock()
        backgroundFor(Duration.ofMinutes(15).plusMillis(1))
        assertTrue(lock.locked.value)
    }

    @Test fun sixteenMinutesLocks() {
        lock.unlock()
        backgroundFor(Duration.ofMinutes(16))
        assertTrue(lock.locked.value)
    }

    @Test fun repeatedBackgroundKeepsFirstTimestamp() {
        lock.unlock()
        lock.onBackground()
        mono.advance(Duration.ofMinutes(10))
        lock.onBackground()
        mono.advance(Duration.ofMinutes(6))
        lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test fun foregroundWithoutBackgroundIsNoOp() {
        lock.unlock()
        lock.onForeground()
        assertFalse(lock.locked.value)
    }

    /** S11 / AC-01 */
    @Test fun onlyRescuerAndPiiAreGuarded() {
        assertTrue(lock.isGuarded(Capability.RESCUER_MODE))
        assertTrue(lock.isGuarded(Capability.VICTIM_PII_DETAIL))
        assertFalse(lock.isGuarded(Capability.VICTIM_MODE))
        assertFalse(lock.isGuarded(Capability.RELAY_MODE))
        assertFalse(lock.isGuarded(Capability.PASSIVE_SCAN))
    }
}
