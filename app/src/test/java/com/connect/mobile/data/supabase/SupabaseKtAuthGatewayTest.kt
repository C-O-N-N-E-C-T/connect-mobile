package com.connect.mobile.data.supabase

import com.connect.mobile.auth.AuthError
import com.connect.mobile.auth.Events
import com.connect.mobile.auth.FakeBackend
import com.connect.mobile.auth.InMemorySessionStore
import com.connect.mobile.auth.LogoutUseCase
import com.connect.mobile.data.session.SessionStore
import com.connect.mobile.data.session.StoreBackedSessionManager
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

/**
 * Exception routing of the real supabase-kt 3.9.0 client (review R12), with a Ktor MockEngine in
 * place of the network. Real time (runBlocking), not runTest: supabase-kt works on its own threads.
 */
class SupabaseKtAuthGatewayTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun gateway(
        store: SessionStore = InMemorySessionStore(),
        initTimeoutMs: Long = 5_000,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("no network expected") },
    ): SupabaseKtAuthGateway = checkNotNull(
        SupabaseKtAuthGateway.createOrNull(
            supabaseUrl = "https://example.supabase.co",
            supabaseAnonKey = "test-anon-key",
            store = store,
            engine = MockEngine { req -> requests += req; handler(req) },
            enableLifecycleCallbacks = false,
            initTimeoutMs = initTimeoutMs,
        ),
    )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun jwt(sub: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString("""{"sub":"$sub","exp":4102444800}""".toByteArray())
        return "$header.$payload.test-signature"
    }

    private fun sessionJson(withUser: Boolean = true) = """
        {"access_token":"${jwt("jwt-sub")}","refresh_token":"test-refresh","expires_in":3600,
         "token_type":"bearer"${if (withUser) ""","user":{"id":"user-1","aud":"authenticated"}""" else ""}}
    """.trimIndent()

    private fun error(status: Int, code: String) =
        """{"code":$status,"error_code":"$code","msg":"test message"}"""

    private fun signInWith(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = runBlocking {
        gateway(handler = handler).signIn("x@y.io", "pw")
    }

    @Test fun successSavesSessionAndExposesUserId() = runBlocking {
        val store = InMemorySessionStore()
        val g = gateway(store) { json(sessionJson()) }
        assertEquals(SignInOutcome.Success, g.signIn("x@y.io", "pw"))
        assertTrue(requests.single().url.toString().contains("/auth/v1/token"))
        assertNotNull(store.session)
        assertEquals("user-1", g.currentUserIdOrNull())
        assertNotNull(g.accessTokenOrNull())
    }

    /** R5 fallback: without a user object the JWT sub is the user id. */
    @Test fun userIdFallsBackToJwtSub() = runBlocking {
        val g = gateway { json(sessionJson(withUser = false)) }
        g.signIn("x@y.io", "pw")
        assertEquals("jwt-sub", g.currentUserIdOrNull())
    }

    @Test fun networkFailureIsNetwork() {
        assertEquals(SignInOutcome.Failure(AuthError.Network), signInWith { throw IOException("offline") })
    }

    @Test fun authRestErrorCodesAreMapped() {
        val cases = listOf(
            400 to "invalid_credentials" to AuthError.BadCredentials,
            400 to "email_not_confirmed" to AuthError.AccountNotReady,
            403 to "user_banned" to AuthError.AccountDisabled,
            429 to "over_request_rate_limit" to AuthError.RateLimited,
        )
        for ((statusAndCode, expected) in cases) {
            val (status, code) = statusAndCode
            val outcome = signInWith { json(error(status, code), HttpStatusCode.fromValue(status)) }
            assertEquals("$status $code", SignInOutcome.Failure(expected), outcome)
        }
    }

    @Test fun restErrorWithoutCodeIsMappedByStatus() {
        val outcome = signInWith {
            respond("<html>bad gateway</html>", HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "text/html"))
        }
        assertEquals(SignInOutcome.Failure(AuthError.Server), outcome)
    }

    @Test fun unexpectedFailureIsServer() {
        // 200 with a body that is not a session.
        assertEquals(SignInOutcome.Failure(AuthError.Server), signInWith { json("""{"unexpected":true}""") })
    }

    /** The stored session is loaded asynchronously; reads wait for it (design 3.2). */
    @Test fun readsWaitForTheStoredSessionToLoad() = runBlocking {
        val store = InMemorySessionStore()
        StoreBackedSessionManager(store).saveSession(
            UserSession(accessToken = "stored-access", refreshToken = "stored-refresh", expiresIn = 3600, tokenType = "bearer", user = null),
        )
        val g = gateway(store)
        assertEquals("stored-access", g.accessTokenOrNull())
    }

    /** R12: a session load that never completes is bounded, and logout still clears everything. */
    @Test fun hangingInitialisationStillLetsLogoutWipe() = runBlocking {
        val events = Events()
        val store = object : SessionStore by InMemorySessionStore(events) {
            override suspend fun readSupabaseSession(): String = awaitCancellation()
        }
        val g = gateway(store, initTimeoutMs = 200)
        withTimeout(10_000) { LogoutUseCase(g, FakeBackend(events), store)(remote = true) }
        assertTrue(events.toString(), events.containsAll(listOf("clearGrant", "clearAuth")))
    }

    /** R7: after the local wipe, a late session import (e.g. an escaped refresh) is not persisted. */
    @Test fun noRePersistAfterClearLocalSession() = runBlocking {
        val store = InMemorySessionStore()
        val g = gateway(store) { json(sessionJson()) }
        g.signIn("x@y.io", "pw")
        val session = checkNotNull(g.auth.currentSessionOrNull())
        g.clearLocalSession()
        assertNull(store.session)
        g.auth.importSession(session)
        assertNull(store.session)
        // The next sign-in re-opens the save gate.
        g.signIn("x@y.io", "pw")
        assertNotNull(store.session)
    }
}
