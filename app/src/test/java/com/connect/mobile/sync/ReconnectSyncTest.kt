package com.connect.mobile.sync

import com.connect.mobile.auth.AuthError
import com.connect.mobile.auth.Events
import com.connect.mobile.auth.InMemorySessionStore
import com.connect.mobile.auth.LogoutUseCase
import com.connect.mobile.auth.FakeBackend
import com.connect.mobile.auth.FakeGateway
import com.connect.mobile.data.backend.BackendError
import com.connect.mobile.data.backend.BackendResult
import com.connect.mobile.data.backend.SyncBackend
import com.connect.mobile.data.supabase.RefreshOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * OSP-76 acceptance checks for the reconnection gate. Fakes only: no network, no Supabase, no DB.
 * The gate is driven with a fixed clock so token expiry is deterministic.
 */
class ReconnectSyncTest {
    private val now: Instant = Instant.parse("2026-11-24T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val events = Events()
    private val gateway = FakeGateway(events)
    private val backend = ScriptedSyncBackend(events)
    private val outbox = MemoryOutbox(
        listOf(record("r1"), record("r2"), record("r3")),
    )
    private val store = InMemorySessionStore(events).apply { deviceId = FakeBackend.DEVICE_ID }
    private val logout = LogoutUseCase(gateway, FakeBackend(events), store)

    private fun gate() = ReconnectSync(gateway, backend, outbox, logout, clock)

    /** A token whose `exp` is [secondsFromNow] after the fixed clock; unsigned, as the app only reads it. */
    private fun token(secondsFromNow: Long): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val exp = now.plusSeconds(secondsFromNow).epochSecond
        val payload = enc.encodeToString("""{"sub":"u1","exp":$exp}""".toByteArray())
        return "$header.$payload.sig"
    }

    private fun record(id: String) = OutboxRecord(id, JsonObject(mapOf("n" to JsonPrimitive(id))))

    // --- AC-19: never send an expired token -------------------------------------------------------

    @Test fun neverSendsAnExpiredAccessToken() = runTest {
        // The refresh returns a token that is already expired on this device's clock: the gate refuses it.
        gateway.refreshOutcomes = mutableListOf(RefreshOutcome.Refreshed(token(-60)))

        val outcome = gate().run()

        assertTrue(outcome is SyncOutcome.SignInRequired)
        assertTrue("no /sync request may carry an expired token", backend.sentTokens.isEmpty())
        assertEquals(3, outbox.remaining().size)
    }

    @Test fun everyUploadUsesAFreshlyRefreshedToken() = runTest {
        val fresh = token(3600)
        gateway.refreshOutcomes = mutableListOf(RefreshOutcome.Refreshed(fresh))

        val outcome = gate().run()

        assertEquals(SyncOutcome.Completed(3), outcome)
        assertEquals(listOf(fresh, fresh, fresh), backend.sentTokens)
        assertTrue(outbox.remaining().isEmpty())
    }

    // --- Step 1: refresh before the first upload -------------------------------------------------

    @Test fun refreshHappensBeforeAnyUpload() = runTest {
        gateway.refreshOutcomes = mutableListOf(RefreshOutcome.Refreshed(token(3600)))

        gate().run()

        assertEquals("refreshSession", events.first())
        assertTrue(events.indexOf("refreshSession") < events.indexOf("sync:r1"))
    }

    // --- Step 2: network error keeps the whole queue -----------------------------------------------

    @Test fun networkErrorKeepsEveryQueuedRecord() = runTest {
        gateway.refreshOutcomes = mutableListOf(RefreshOutcome.Refreshed(token(3600)))
        backend.script = mutableListOf(BackendResult.Err(BackendError.Network))

        val outcome = gate().run()

        assertEquals(SyncOutcome.RetryLater(3), outcome)
        assertEquals(listOf("r1", "r2", "r3"), outbox.remaining().map { it.id })
    }

    @Test fun transientRefreshErrorKeepsTheQueueAndAsksForRetry() = runTest {
        gateway.refreshOutcomes = mutableListOf(RefreshOutcome.Transient)

        val outcome = gate().run()

        assertEquals(SyncOutcome.RetryLater(3), outcome)
        assertTrue(backend.sentTokens.isEmpty())
        assertEquals(3, outbox.remaining().size)
    }

    // --- Step 3: refresh refused or 401 keeps the queue and asks for an online sign-in ------------

    @Test fun refusedRefreshKeepsTheQueueAndRequiresSignIn() = runTest {
        gateway.refreshOutcomes = mutableListOf(RefreshOutcome.Rejected)

        val outcome = gate().run()

        assertEquals(SyncOutcome.SignInRequired(3), outcome)
        assertEquals(3, outbox.remaining().size)
    }

    @Test fun unauthorizedTwiceRequiresSignInWithoutDeletingAnything() = runTest {
        gateway.refreshOutcomes = mutableListOf(RefreshOutcome.Refreshed(token(3600)))
        backend.script = mutableListOf(
            BackendResult.Err(BackendError.Http(401, "UNAUTHENTICATED", null)),
            BackendResult.Err(BackendError.Http(401, "UNAUTHENTICATED", null)),
        )

        val outcome = gate().run()

        assertEquals(SyncOutcome.SignInRequired(3), outcome)
        assertEquals(3, outbox.remaining().size)
        assertEquals(2, gateway.refreshCalls)
    }

    @Test fun unauthorizedOnceIsRecoveredByOneRefresh() = runTest {
        gateway.refreshOutcomes = mutableListOf(RefreshOutcome.Refreshed(token(3600)))
        backend.script = mutableListOf(
            BackendResult.Err(BackendError.Http(401, "UNAUTHENTICATED", null)),
        )

        val outcome = gate().run()

        assertEquals(SyncOutcome.Completed(3), outcome)
        assertTrue(outbox.remaining().isEmpty())
    }

    // --- Step 4: a record leaves the queue only after a 2xx ---------------------------------------

    @Test fun recordIsRemovedOnlyAfterItsUploadSucceeded() = runTest {
        gateway.refreshOutcomes = mutableListOf(RefreshOutcome.Refreshed(token(3600)))
        backend.script = mutableListOf(
            BackendResult.Ok(Unit),
            BackendResult.Err(BackendError.Network),
        )

        val outcome = gate().run()

        assertEquals(SyncOutcome.RetryLater(2), outcome)
        assertEquals(listOf("r2", "r3"), outbox.remaining().map { it.id })
        assertEquals(listOf("r1"), outbox.sentIds())
    }

    @Test fun uploadsKeepQueueOrder() = runTest {
        gateway.refreshOutcomes = mutableListOf(RefreshOutcome.Refreshed(token(3600)))

        gate().run()

        assertEquals(listOf("r1", "r2", "r3"), backend.sentPayloadIds)
    }

    // --- Step 5: 403 revoked / disabled aborts, wipes tokens + grant, keeps the records -----------

    @Test fun deviceRevokedAbortsAndKeepsEveryRecord() = runTest {
        gateway.refreshOutcomes = mutableListOf(RefreshOutcome.Refreshed(token(3600)))
        backend.script = mutableListOf(BackendResult.Err(BackendError.Http(403, "DEVICE_REVOKED", null)))

        val outcome = gate().run()

        assertEquals(SyncOutcome.Locked(AuthError.DeviceRevoked, 3), outcome)
        assertEquals(3, outbox.remaining().size)
        // Local wipe only: no further remote call with the revoked token, and the upload stopped.
        assertEquals(1, backend.sentTokens.size)
        assertTrue(events.contains("clearLocalSession"))
        assertTrue(events.contains("clearAuth"))
    }

    @Test fun accountDisabledAbortsAndKeepsEveryRecord() = runTest {
        gateway.refreshOutcomes = mutableListOf(RefreshOutcome.Refreshed(token(3600)))
        backend.script = mutableListOf(BackendResult.Err(BackendError.Http(403, "ACCOUNT_DISABLED", null)))

        val outcome = gate().run()

        assertEquals(SyncOutcome.Locked(AuthError.AccountDisabled, 3), outcome)
        assertEquals(3, outbox.remaining().size)
        assertEquals(1, backend.sentTokens.size)
    }

    @Test fun lockDoesNotTouchTheDatabaseKeyStore() = runTest {
        // The gate has no reference to DbKeyProvider at all; this asserts the store that LogoutUseCase
        // receives (the session store) never reports a key-related event on lock.
        gateway.refreshOutcomes = mutableListOf(RefreshOutcome.Refreshed(token(3600)))
        backend.script = mutableListOf(BackendResult.Err(BackendError.Http(403, "DEVICE_REVOKED", null)))

        gate().run()

        assertFalse(events.any { it.contains("db", ignoreCase = true) || it.contains("key", ignoreCase = true) })
    }

    @Test fun sessionNotBoundRequiresSignInNotLock() = runTest {
        gateway.refreshOutcomes = mutableListOf(RefreshOutcome.Refreshed(token(3600)))
        backend.script = mutableListOf(BackendResult.Err(BackendError.Http(403, "SESSION_NOT_BOUND", null)))

        val outcome = gate().run()

        assertEquals(SyncOutcome.SignInRequired(3), outcome)
        assertEquals(3, outbox.remaining().size)
    }

    // --- Empty queue and backoff ------------------------------------------------------------------

    @Test fun emptyQueueDoesNotRefreshOrCallTheBackend() = runTest {
        val empty = MemoryOutbox(emptyList())
        val g = ReconnectSync(gateway, backend, empty, logout, clock)

        assertEquals(SyncOutcome.Completed(0), g.run())
        assertEquals(0, gateway.refreshCalls)
        assertTrue(backend.sentTokens.isEmpty())
    }

    @Test fun backoffDoublesAndIsCapped() {
        val b = SyncBackoff(initial = Duration.ofSeconds(5), max = Duration.ofMinutes(5))
        assertEquals(Duration.ofSeconds(5), b.delayFor(0))
        assertEquals(Duration.ofSeconds(10), b.delayFor(1))
        assertEquals(Duration.ofSeconds(20), b.delayFor(2))
        assertEquals(Duration.ofMinutes(5), b.delayFor(20))
    }
}

/** Upload sink with scripted results; every call records the token and the payload id. */
class ScriptedSyncBackend(private val events: Events = Events()) : SyncBackend {
    var script: MutableList<BackendResult<Unit>> = mutableListOf()
    val sentTokens = mutableListOf<String>()
    val sentPayloadIds = mutableListOf<String>()

    override suspend fun upload(accessToken: String, payload: JsonObject): BackendResult<Unit> {
        sentTokens += accessToken
        val id = (payload["n"] as JsonPrimitive).content
        sentPayloadIds += id
        events += "sync:$id"
        return if (script.isNotEmpty()) script.removeAt(0) else BackendResult.Ok(Unit)
    }
}

/** In-memory queue honouring the contract: removal only through markSent. */
class MemoryOutbox(initial: List<OutboxRecord>) : OutboxStore {
    private val queue = initial.toMutableList()
    private val sent = mutableListOf<String>()

    override suspend fun pending(): List<OutboxRecord> = queue.toList()

    override suspend fun markSent(id: String) {
        queue.removeAll { it.id == id }
        sent += id
    }

    fun remaining(): List<OutboxRecord> = queue.toList()
    fun sentIds(): List<String> = sent.toList()
}
