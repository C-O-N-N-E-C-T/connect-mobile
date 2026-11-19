package com.connect.mobile.data.supabase

import com.connect.mobile.auth.AuthError
import com.connect.mobile.data.session.SessionStore
import com.connect.mobile.data.session.StoreBackedSessionManager
import com.connect.mobile.util.AppLogger
import com.connect.mobile.util.causeChainMessages
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.Base64

class SupabaseKtAuthGateway(
    internal val auth: Auth, // internal for tests only
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

    companion object {
        /**
         * Builds the SupabaseClient with the app's Auth configuration (design 3.2). Returns null for an
         * unusable config: supabase-kt throws IllegalArgumentException / IllegalStateException (e.g. a
         * SUPABASE_URL containing "auth/v1"), and the app must then show "not configured", not crash.
         */
        fun createOrNull(
            supabaseUrl: String,
            supabaseAnonKey: String,
            store: SessionStore,
            log: AppLogger = AppLogger.NONE,
            engine: HttpClientEngine = OkHttp.create(),
            enableLifecycleCallbacks: Boolean = true,
            initTimeoutMs: Long = 5_000,
        ): SupabaseKtAuthGateway? = try {
            val sessionManager = StoreBackedSessionManager(store)
            val client = createSupabaseClient(supabaseUrl, supabaseAnonKey) {
                // Same OkHttp engine type as the backend client, so the Network Security Config covers it.
                httpEngine = engine
                // supabase-kt exception/log messages can include request URLs and (masked) headers:
                // keep its logger off; AppLogger covers our own diagnostics without tokens.
                defaultLogLevel = LogLevel.NONE
                install(Auth) {
                    // Tokens live only in SessionStore, not in plaintext SharedPreferences (design 3.2).
                    this.sessionManager = sessionManager
                    codeVerifierCache = MemoryCodeVerifierCache()
                    this.enableLifecycleCallbacks = enableLifecycleCallbacks
                    // TODO(OSP-75): suspend auto-refresh while offline.
                }
            }
            SupabaseKtAuthGateway(client.auth, sessionManager, log, initTimeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("invalid Supabase config: ${e.causeChainMessages()}")
            null
        }
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
