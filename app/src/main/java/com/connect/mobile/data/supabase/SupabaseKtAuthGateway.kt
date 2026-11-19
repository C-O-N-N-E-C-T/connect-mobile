package com.connect.mobile.data.supabase

import com.connect.mobile.auth.AuthError
import com.connect.mobile.data.session.StoreBackedSessionManager
import com.connect.mobile.util.AppLogger
import com.connect.mobile.util.causeChainMessages
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.Base64

class SupabaseKtAuthGateway(
    private val auth: Auth,
    private val sessionManager: StoreBackedSessionManager,
    private val log: AppLogger = AppLogger.NONE,
    private val initTimeoutMs: Long = 5_000,
) : SupabaseAuthGateway {

    override suspend fun awaitInitialized() {
        withTimeoutOrNull(initTimeoutMs) { auth.awaitInitialization() }
    }

    override suspend fun signIn(email: String, password: String): SignInOutcome {
        awaitInitialized()
        // supabase-kt saves the new session inside signInWith, so re-open the save gate first.
        sessionManager.allowSaves()
        return try {
            auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            SignInOutcome.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthRestException) {
            log.debug("supabase sign-in rejected: ${e.errorCode?.value} (${e.statusCode})")
            SignInOutcome.Failure(SupabaseErrorMapper.fromRest(e.errorCode?.value, e.statusCode))
        } catch (e: RestException) {
            log.debug("supabase sign-in rejected: ${e.error} (${e.statusCode})")
            SignInOutcome.Failure(SupabaseErrorMapper.fromRest(null, e.statusCode))
        } catch (e: IOException) {
            // supabase-kt HttpRequestException / timeouts / the OS cleartext refusal. Only the causes
            // are logged: supabase-kt's own message contains the request URL.
            log.debug("supabase network failure: ${e.javaClass.simpleName} <- ${e.cause?.causeChainMessages()}")
            SignInOutcome.Failure(AuthError.Network)
        } catch (e: Exception) {
            log.debug("supabase sign-in failure: ${e.javaClass.simpleName}")
            SignInOutcome.Failure(AuthError.Server)
        }
    }

    override suspend fun accessTokenOrNull(): String? {
        awaitInitialized()
        return auth.currentAccessTokenOrNull()
    }

    override suspend fun currentUserIdOrNull(): String? {
        awaitInitialized()
        val session = auth.currentSessionOrNull() ?: return null
        // The password-grant response carries the user; the JWT `sub` is the same id (fallback).
        return session.user?.id ?: jwtSubject(session.accessToken)
    }

    override suspend fun signOut() {
        auth.signOut(SignOutScope.LOCAL)
    }

    override suspend fun clearLocalSession() {
        // Review R7: close the save gate and stop auto-refresh on both sides of clearSession(), so a
        // refresh job recreated concurrently cannot escape and re-persist the tokens.
        sessionManager.blockSaves()
        auth.stopAutoRefreshForCurrentSession()
        auth.clearSession()
        auth.stopAutoRefreshForCurrentSession()
    }
}

/** `sub` claim of a JWT, decoded WITHOUT verification (only compared with the backend's answer). */
internal fun jwtSubject(jwt: String): String? = runCatching {
    val payload = jwt.split('.')[1]
    val text = String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8)
    (Json.parseToJsonElement(text).jsonObject["sub"] as? JsonPrimitive)?.contentOrNull
}.getOrNull()

/** Used when SUPABASE_URL / SUPABASE_ANON_KEY are missing: no client is created at all. */
class NotConfiguredAuthGateway(private val deleteStoredSession: suspend () -> Unit) : SupabaseAuthGateway {
    override suspend fun awaitInitialized() = Unit
    override suspend fun signIn(email: String, password: String) = SignInOutcome.Failure(AuthError.NotConfigured)
    override suspend fun accessTokenOrNull(): String? = null
    override suspend fun currentUserIdOrNull(): String? = null
    override suspend fun signOut() = Unit
    override suspend fun clearLocalSession() = deleteStoredSession()
}
