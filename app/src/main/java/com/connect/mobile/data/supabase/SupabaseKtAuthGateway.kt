package com.connect.mobile.data.supabase

import com.connect.mobile.auth.AuthError
import com.connect.mobile.util.AppLogger
import com.connect.mobile.util.causeChainMessages
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class SupabaseKtAuthGateway(
    private val auth: Auth,
    private val log: AppLogger = AppLogger.NONE,
    private val initTimeoutMs: Long = 5_000,
) : SupabaseAuthGateway {

    override suspend fun awaitInitialized() {
        withTimeoutOrNull(initTimeoutMs) { auth.awaitInitialization() }
    }

    override suspend fun signIn(email: String, password: String): SignInOutcome {
        awaitInitialized()
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
            // supabase-kt HttpRequestException / timeouts / the OS cleartext refusal.
            log.debug("supabase network failure: ${e.causeChainMessages()}")
            SignInOutcome.Failure(AuthError.Network)
        } catch (e: Exception) {
            log.debug("supabase sign-in failure: ${e.causeChainMessages()}")
            SignInOutcome.Failure(AuthError.Network)
        }
    }

    override suspend fun accessTokenOrNull(): String? {
        awaitInitialized()
        return auth.currentAccessTokenOrNull()
    }

    override suspend fun signOut() {
        auth.signOut(SignOutScope.LOCAL)
    }

    override suspend fun clearLocalSession() {
        auth.clearSession()
    }
}

/** Used when SUPABASE_URL / SUPABASE_ANON_KEY are missing: no client is created at all. */
class NotConfiguredAuthGateway(private val deleteStoredSession: suspend () -> Unit) : SupabaseAuthGateway {
    override suspend fun awaitInitialized() = Unit
    override suspend fun signIn(email: String, password: String) = SignInOutcome.Failure(AuthError.NotConfigured)
    override suspend fun accessTokenOrNull(): String? = null
    override suspend fun signOut() = Unit
    override suspend fun clearLocalSession() = deleteStoredSession()
}
