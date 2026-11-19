package com.connect.mobile.auth

import com.connect.mobile.data.backend.AuthBackend
import com.connect.mobile.data.backend.BackendError
import com.connect.mobile.data.backend.BackendResult
import com.connect.mobile.data.backend.OfflineMissionGrantDto
import com.connect.mobile.data.backend.SessionResponse
import com.connect.mobile.data.device.DeviceIdProvider
import com.connect.mobile.data.session.SessionStore
import com.connect.mobile.data.session.StoredGrant
import com.connect.mobile.data.supabase.SignInOutcome
import com.connect.mobile.data.supabase.SupabaseAuthGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.OffsetDateTime

enum class LoginStep { SigningIn, BindingSession, TearingDown }

sealed interface LoginResult {
    data class Success(val grant: StoredGrant) : LoginResult
    data class Failure(val error: AuthError) : LoginResult
}

/**
 * Supabase e-mail/password sign-in -> POST /api/v1/auth/session -> role gate (rescuer only) ->
 * grant sanity check -> persist grant. Any failure after sign-in runs the full teardown; a cancelled
 * login wipes locally.
 *
 * Only SessionResponse.role decides; the JWT user_role claim is never used for decisions.
 */
class LoginUseCase(
    private val isConfigured: Boolean,
    private val gateway: SupabaseAuthGateway,
    private val backend: AuthBackend,
    private val store: SessionStore,
    private val deviceIdProvider: DeviceIdProvider,
    private val logout: LogoutUseCase,
) {
    suspend operator fun invoke(
        email: String,
        password: String,
        onStep: (LoginStep) -> Unit = {},
    ): LoginResult {
        if (!isConfigured) return LoginResult.Failure(AuthError.NotConfigured)
        val trimmedEmail = email.trim()
        if (trimmedEmail.isEmpty() || password.isEmpty() || !EMAIL.matches(trimmedEmail)) {
            return LoginResult.Failure(AuthError.InvalidInput)
        }

        onStep(LoginStep.SigningIn)
        return try {
            signInAndBind(trimmedEmail, password, onStep)
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                // A stray CancellationException (e.g. a nested timeout) while we are still running.
                fail(AuthError.Server, remote = true, onStep)
            } else {
                // The caller left (e.g. the screen closed) after sign-in: tokens may already be
                // persisted, so wipe them locally before rethrowing (review R6).
                withContext(NonCancellable) { logout(remote = false) }
                throw e
            }
        } catch (e: Exception) {
            // Store error (e.g. disk full in writeGrant) or anything unexpected: never leave tokens behind.
            fail(AuthError.Server, remote = true, onStep)
        }
    }

    private suspend fun signInAndBind(
        email: String,
        password: String,
        onStep: (LoginStep) -> Unit,
    ): LoginResult {
        when (val outcome = gateway.signIn(email, password)) {
            is SignInOutcome.Failure -> return fail(outcome.error, remote = false, onStep)
            SignInOutcome.Success -> Unit
        }
        val token = gateway.accessTokenOrNull()
            ?: return fail(AuthError.SessionRejected, remote = false, onStep)

        onStep(LoginStep.BindingSession)
        val deviceId = deviceIdProvider.deviceId()
        val response = when (val r = backend.bindSession(token, deviceId, deviceIdProvider.deviceName())) {
            is BackendResult.Err -> return fail(r.error.toAuthError(), remote = true, onStep)
            is BackendResult.Ok -> r.value
        }

        // OSP-60 ASSUMPTION: a coordinator/administrator may get 200 with role != "rescuer" (handled here) or 403 ROLE_FORBIDDEN (handled in toAuthError) — see docs/osp-60-contract-assumptions.md
        if (response.role != RESCUER_ROLE) {
            return fail(AuthError.RoleNotAllowed, remote = true, onStep)
        }
        val grant = validateGrant(response, deviceId)
            ?: return fail(AuthError.GrantInvalid, remote = true, onStep)

        store.writeGrant(grant)
        return LoginResult.Success(grant)
    }

    private suspend fun fail(
        error: AuthError,
        remote: Boolean,
        onStep: (LoginStep) -> Unit,
    ): LoginResult {
        onStep(LoginStep.TearingDown)
        logout(remote = remote)
        return LoginResult.Failure(error)
    }

    companion object {
        // OSP-60 ASSUMPTION: role is the lowercase public.user_profiles value ("rescuer" | "coordinator" | "administrator") — see docs/osp-60-contract-assumptions.md
        const val RESCUER_ROLE = "rescuer"
        val MAX_GRANT: Duration = Duration.ofHours(72).plusSeconds(60)
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        /** Client-side sanity check only; expiry over time is OSP-73's OfflineGrantPolicy. */
        fun validateGrant(response: SessionResponse, ourDeviceId: String): StoredGrant? {
            val dto: OfflineMissionGrantDto = response.grant ?: return null
            // OSP-60 ASSUMPTION: grant.user_id == response user_id, grant.device_id == the device_id we sent, 0 < expires_at - authenticated_at <= 72 h (+60 s tolerance) — see docs/osp-60-contract-assumptions.md
            if (dto.userId != response.userId || dto.deviceId != ourDeviceId) return null
            // OSP-60 ASSUMPTION: authenticated_at / expires_at are ISO-8601 with offset ("…Z" or "+00:00") — see docs/osp-60-contract-assumptions.md
            val authenticatedAt = runCatching { OffsetDateTime.parse(dto.authenticatedAt).toInstant() }.getOrNull()
                ?: return null
            val expiresAt = runCatching { OffsetDateTime.parse(dto.expiresAt).toInstant() }.getOrNull()
                ?: return null
            val validity = Duration.between(authenticatedAt, expiresAt)
            if (validity.isNegative || validity.isZero || validity > MAX_GRANT) return null
            return StoredGrant(
                userId = dto.userId,
                deviceId = dto.deviceId,
                role = response.role,
                authenticatedAt = authenticatedAt,
                expiresAt = expiresAt,
            )
        }
    }
}

// OSP-60 ASSUMPTION: error_code values and statuses handled by the app (connect-backend src/types/errors.ts) — see docs/osp-60-contract-assumptions.md
internal fun BackendError.toAuthError(): AuthError = when (this) {
    BackendError.Network -> AuthError.Network
    BackendError.Malformed -> AuthError.GrantInvalid
    is BackendError.Http -> when {
        code == "ROLE_FORBIDDEN" -> AuthError.RoleNotAllowed
        code == "UNAUTHENTICATED" -> AuthError.SessionRejected
        code == "ACCOUNT_DISABLED" -> AuthError.AccountDisabled
        code == "DEVICE_REVOKED" -> AuthError.DeviceRevoked
        code == "RATE_LIMITED" || status == 429 -> AuthError.RateLimited
        code == "VALIDATION_ERROR" -> AuthError.RequestRejected
        else -> AuthError.Server // SESSION_NOT_BOUND, NOT_FOUND, INTERNAL_ERROR, unknown
    }
}
