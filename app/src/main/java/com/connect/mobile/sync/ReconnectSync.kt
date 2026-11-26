package com.connect.mobile.sync

import com.connect.mobile.auth.AuthError
import com.connect.mobile.auth.LogoutUseCase
import com.connect.mobile.data.backend.BackendError
import com.connect.mobile.data.backend.BackendResult
import com.connect.mobile.data.backend.SyncBackend
import com.connect.mobile.data.supabase.RefreshOutcome
import com.connect.mobile.data.supabase.SupabaseAuthGateway
import com.connect.mobile.data.supabase.jwtExpiresAt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration

/** OSP-76: result of one pass of the reconnection gate. [pending] = records still queued afterwards. */
sealed interface SyncOutcome {
    /** Every queued record was uploaded (or there was nothing to upload). */
    data class Completed(val uploaded: Int) : SyncOutcome

    /** Network error / timeout / temporary server error: queue kept, retry with backoff (step 2). */
    data class RetryLater(val pending: Int) : SyncOutcome

    /** Refresh refused or 401 after one refresh: queue kept, rescuer must sign in online (step 3). */
    data class SignInRequired(val pending: Int) : SyncOutcome

    /**
     * 403 DEVICE_REVOKED / ACCOUNT_DISABLED (step 5): upload aborted, tokens and the offline grant
     * deleted. The queue and the encrypted database key are NOT touched.
     */
    data class Locked(val reason: AuthError, val pending: Int) : SyncOutcome
}

/** Lets the ViewModel run the gate without depending on the concrete class (tests use a fake). */
fun interface SyncRunner {
    suspend fun run(): SyncOutcome
}

/**
 * OSP-76 reconnection gate (FR-R-12 -> FR-B-01), run when connectivity returns:
 *
 * 1. Refresh the session first; an expired access token is never sent to POST /api/v1/sync (AC-19).
 * 2. Network error / timeout: keep 100 % of the queue, return [SyncOutcome.RetryLater].
 * 3. Refresh refused, or 401 after one refresh: keep the queue, return [SyncOutcome.SignInRequired];
 *    the online sign-in calls POST /api/v1/auth/session (LoginUseCase).
 * 4. With a fresh token, upload the queued records in order; a record is removed only after 2xx.
 * 5. 403 DEVICE_REVOKED / ACCOUNT_DISABLED: abort, delete tokens + grant through [LogoutUseCase]
 *    (remote = false), return [SyncOutcome.Locked]. LogoutUseCase has no database handle, so it cannot
 *    reach the queue or the SQLCipher key: unsynced life-safety records survive a deactivation.
 *
 * 403 from POST /api/v1/auth/session is handled by the existing sign-in flow (LoginUseCase), which
 * maps it to AuthError.DeviceRevoked / AccountDisabled and runs the same teardown.
 */
class ReconnectSync(
    private val gateway: SupabaseAuthGateway,
    private val backend: SyncBackend,
    private val outbox: OutboxStore,
    private val logout: LogoutUseCase,
    private val clock: Clock = Clock.systemUTC(),
    /** A token that expires within this margin counts as expired (clock skew, request time). */
    private val expiryMargin: Duration = Duration.ofSeconds(30),
) : SyncRunner {
    // Connectivity can flap; two passes must never upload the same record twice.
    private val mutex = Mutex()

    override suspend fun run(): SyncOutcome = mutex.withLock { runOnce() }

    private suspend fun runOnce(): SyncOutcome {
        val queue = outbox.pending()
        // Nothing to upload: no refresh, no request, no sign-in prompt.
        if (queue.isEmpty()) return SyncOutcome.Completed(0)

        // Step 1: refresh BEFORE the first upload.
        var token = when (val fresh = freshToken()) {
            is Token.Ok -> fresh.value
            is Token.Stop -> return fresh.outcome(queue.size)
        }

        var uploaded = 0
        for (record in queue) {
            // AC-19 guard: the token may expire while a long queue is being uploaded.
            if (!isUsable(token)) {
                token = when (val fresh = freshToken()) {
                    is Token.Ok -> fresh.value
                    is Token.Stop -> return fresh.outcome(queue.size - uploaded)
                }
            }

            var result = backend.upload(token, record.payload)
            if (result.isUnauthenticated()) {
                // One refresh and one retry; a second 401 means the session is no longer accepted.
                token = when (val fresh = freshToken()) {
                    is Token.Ok -> fresh.value
                    is Token.Stop -> return fresh.outcome(queue.size - uploaded)
                }
                result = backend.upload(token, record.payload)
                if (result.isUnauthenticated()) return SyncOutcome.SignInRequired(queue.size - uploaded)
            }

            when (val step = classify(result)) {
                Step.Sent -> {
                    outbox.markSent(record.id)
                    uploaded++
                }
                Step.Retry -> return SyncOutcome.RetryLater(queue.size - uploaded)
                Step.SignIn -> return SyncOutcome.SignInRequired(queue.size - uploaded)
                is Step.Lock -> {
                    // Step 5: local wipe only (LogoutUseCase never throws). No remote calls with a revoked token.
                    logout(remote = false)
                    return SyncOutcome.Locked(step.reason, queue.size - uploaded)
                }
            }
        }
        return SyncOutcome.Completed(uploaded)
    }

    /** Refreshes and refuses a token that is already expired on this device's clock. */
    private suspend fun freshToken(): Token = when (val r = gateway.refreshSession()) {
        is RefreshOutcome.Refreshed ->
            if (isUsable(r.accessToken)) Token.Ok(r.accessToken) else Token.Stop(Halt.SignIn)
        RefreshOutcome.Transient -> Token.Stop(Halt.Retry)
        RefreshOutcome.Rejected -> Token.Stop(Halt.SignIn)
    }

    /** True only if the token carries an `exp` later than now + margin. Unreadable means unusable. */
    private fun isUsable(token: String): Boolean {
        val expiresAt = jwtExpiresAt(token) ?: return false
        return expiresAt.isAfter(clock.instant().plus(expiryMargin))
    }

    private fun BackendResult<Unit>.isUnauthenticated() =
        this is BackendResult.Err && (error as? BackendError.Http)?.status == 401

    private fun classify(result: BackendResult<Unit>): Step = when (result) {
        is BackendResult.Ok -> Step.Sent
        is BackendResult.Err -> when (val e = result.error) {
            BackendError.Network, BackendError.Malformed -> Step.Retry
            is BackendError.Http -> when {
                e.status == 403 && e.code == "DEVICE_REVOKED" -> Step.Lock(AuthError.DeviceRevoked)
                e.status == 403 && e.code == "ACCOUNT_DISABLED" -> Step.Lock(AuthError.AccountDisabled)
                // The session is not bound to this device: a new online sign-in (/auth/session) fixes it.
                e.status == 403 && e.code == "SESSION_NOT_BOUND" -> Step.SignIn
                e.status == 401 -> Step.SignIn
                // Rate limit, server errors and anything unexpected: keep the record, retry later.
                else -> Step.Retry
            }
        }
    }

    private sealed interface Step {
        data object Sent : Step
        data object Retry : Step
        data object SignIn : Step
        data class Lock(val reason: AuthError) : Step
    }

    private enum class Halt { Retry, SignIn }

    private sealed interface Token {
        data class Ok(val value: String) : Token
        data class Stop(val why: Halt) : Token {
            fun outcome(pending: Int): SyncOutcome = when (why) {
                Halt.Retry -> SyncOutcome.RetryLater(pending)
                Halt.SignIn -> SyncOutcome.SignInRequired(pending)
            }
        }
    }
}

/** OSP-76 step 2: exponential backoff between retries while online (5 s, 10 s, 20 s, ... max 5 min). */
class SyncBackoff(
    private val initial: Duration = Duration.ofSeconds(5),
    private val max: Duration = Duration.ofMinutes(5),
) {
    fun delayFor(attempt: Int): Duration {
        val shift = attempt.coerceIn(0, 16)
        val delay = initial.multipliedBy(1L shl shift)
        return if (delay > max) max else delay
    }
}
