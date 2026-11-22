package com.connect.mobile.data.supabase

import com.connect.mobile.auth.AuthError
import com.connect.mobile.auth.Events
import com.connect.mobile.auth.FakeBackend
import com.connect.mobile.auth.InMemorySessionStore
import com.connect.mobile.auth.LogoutUseCase
import com.connect.mobile.data.session.SessionStore
import com.connect.mobile.data.session.StoreBackedSessionManager
import io.github.jan.supabase.auth.status.SessionStatus
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
        autoRefreshAllowed: () -> Boolean = { true },
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("no network expected") },
    ): SupabaseKtAuthGateway = checkNotNull(
        SupabaseKtAuthGateway.createOrNull(
            supabaseUrl = "https://example.supabase.co",
            supabaseAnonKey = "test-anon-key",
            store = store,
            engine = MockEngine { req -> requests += req; handler(req) },
            initTimeoutMs = initTimeoutMs,
            autoRefreshAllowed = autoRefreshAllowed,
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

    // --- OSP-75: çevrimdışıyken otomatik token yenilemenin askıya alınması ---

    /** Süresi çoktan dolmuş saklı oturum (ör. uzun uçak modu sonrası); supabase-kt alanı `expiresAt`. */
    private fun expiredStoredSession() =
        """{"access_token":"stored-expired-access","refresh_token":"stored-refresh","expires_in":3600,""" +
            """"token_type":"bearer","expiresAt":"2020-01-01T00:00:00Z"}"""

    private fun refreshedSessionJson() =
        """{"access_token":"refreshed-access","refresh_token":"refreshed-refresh","expires_in":3600,"token_type":"bearer"}"""

    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            delay(20)
        }
    }

    private fun refreshRequests() = requests.count { it.url.parameters["grant_type"] == "refresh_token" }

    /** Çevrimdışı açılış: süresi geçmiş oturum yüklenir ama yenileme döngüsü başlamaz, silinmez. */
    @Test fun offlineLoadKeepsExpiredSessionWithoutRefreshLoop() = runBlocking {
        val store = InMemorySessionStore().apply { session = expiredStoredSession() }
        val g = gateway(store, autoRefreshAllowed = { false }) { throw IOException("offline") }
        assertEquals("stored-expired-access", g.accessTokenOrNull())
        delay(500)
        assertEquals(0, requests.size)
        assertTrue(!g.auth.isAutoRefreshRunning)
        assertTrue(g.auth.sessionStatus.value is SessionStatus.Authenticated)
        assertNotNull("offline must not delete the stored session", store.session)
    }

    /** Ağ geri gelince (denetleyici resume) süresi geçmiş token hemen yenilenir ve kaydedilir. */
    @Test fun resumeRefreshesWhenBackOnline() = runBlocking {
        val store = InMemorySessionStore().apply { session = expiredStoredSession() }
        val g = gateway(store, autoRefreshAllowed = { false }) { json(refreshedSessionJson()) }
        g.awaitInitialized()
        assertEquals(0, requests.size)
        g.resumeAutoRefresh()
        waitUntil("refreshed session saved") { store.session?.contains("refreshed-access") == true }
        assertEquals("refreshed-access", g.accessTokenOrNull())
        assertTrue(g.auth.isAutoRefreshRunning)
    }

    /**
     * Çevrimiçi sanılırken yenileme ağ hatasıyla düştü (RefreshFailure): supabase-kt oturumu "current"
     * saymaz; start doğrudan çağrılsa ISE atardı. resume depodan yeniden yükleyip yeniler.
     */
    @Test fun resumeAfterRefreshFailureReloadsFromStorage() = runBlocking {
        val store = InMemorySessionStore().apply { session = expiredStoredSession() }
        var networkUp = false
        val g = gateway(store, autoRefreshAllowed = { true }) {
            if (!networkUp) throw IOException("offline") else json(refreshedSessionJson())
        }
        g.awaitInitialized()
        waitUntil("refresh failure") { g.auth.sessionStatus.value is SessionStatus.RefreshFailure }
        g.pauseAutoRefresh()
        assertNotNull(store.session)

        networkUp = true
        g.resumeAutoRefresh()
        waitUntil("refreshed session") { store.session?.contains("refreshed-access") == true }
        assertTrue(g.auth.sessionStatus.value is SessionStatus.Authenticated)
    }

    @Test fun resumeWithoutSessionDoesNothing() = runBlocking {
        val g = gateway()
        g.resumeAutoRefresh()
        assertEquals(0, requests.size)
        assertTrue(!g.auth.isAutoRefreshRunning)
    }

    @Test fun pauseStopsRunningRefreshAndKeepsSession() = runBlocking {
        val store = InMemorySessionStore()
        val g = gateway(store) { json(sessionJson()) }
        g.signIn("x@y.io", "pw")
        assertTrue(g.auth.isAutoRefreshRunning)
        g.pauseAutoRefresh()
        assertTrue(!g.auth.isAutoRefreshRunning)
        assertNotNull(g.accessTokenOrNull())
        assertNotNull(store.session)
    }

    /** Logout sonrası (kayıt kapısı kapalı) resume oturumu depodan geri getirmez. */
    @Test fun resumeAfterLogoutDoesNotReload() = runBlocking {
        val store = InMemorySessionStore()
        val g = gateway(store) { json(sessionJson()) }
        g.signIn("x@y.io", "pw")
        g.clearLocalSession()
        store.session = sessionJson() // ör. kaçan bir yazma; yine de yüklenmemeli
        val before = requests.size
        g.resumeAutoRefresh()
        assertNull(g.auth.currentSessionOrNull())
        assertEquals(before, requests.size)
    }

    /**
     * O1: logout, resume'un depodan yeniden yüklemesiyle yarışır. Yükleme okuma yaparken logout
     * tamamlanırsa, yüklenen oturum belleğe geri girmemeli (kapı yüklemeden sonra yeniden kontrol edilir).
     */
    @Test fun logoutDuringResumeReloadDoesNotResurrectSession() = runBlocking {
        val inner = InMemorySessionStore().apply { session = expiredStoredSession() }
        var networkUp = false
        var logoutDuringRead = false
        lateinit var g: SupabaseKtAuthGateway
        val store = object : SessionStore by inner {
            override suspend fun readSupabaseSession(): String? {
                val value = inner.readSupabaseSession()
                if (logoutDuringRead) {
                    logoutDuringRead = false
                    g.clearLocalSession() // logout tam bu anda biter
                }
                return value
            }
        }
        g = gateway(store, autoRefreshAllowed = { true }) {
            if (!networkUp) throw IOException("offline") else json(refreshedSessionJson())
        }
        g.awaitInitialized()
        waitUntil("refresh failure") { g.auth.sessionStatus.value is SessionStatus.RefreshFailure }
        g.pauseAutoRefresh()

        networkUp = true
        logoutDuringRead = true
        withTimeout(15_000) { g.resumeAutoRefresh() }
        delay(300)
        assertNull(g.auth.currentSessionOrNull())
        assertTrue(!g.auth.isAutoRefreshRunning)
        assertNull(inner.session)
    }

    /** O3: yükleme sırasında izin kalktıysa (ağ düştü) yüklemenin başlattığı yenileme durdurulur. */
    @Test fun refreshStartedDuringLoadIsStoppedIfOfflineAfterLoad() = runBlocking {
        var allowed = true
        val inner = InMemorySessionStore()
        StoreBackedSessionManager(inner).saveSession(
            UserSession(accessToken = "stored-access", refreshToken = "stored-refresh", expiresIn = 3600, tokenType = "bearer", user = null),
        )
        val store = object : SessionStore by inner {
            override suspend fun readSupabaseSession(): String? {
                allowed = false // yükleme sürerken uçak modu
                return inner.readSupabaseSession()
            }
        }
        val g = gateway(store, autoRefreshAllowed = { allowed })
        assertEquals("stored-access", g.accessTokenOrNull())
        assertTrue(!g.auth.isAutoRefreshRunning)
        assertNotNull(inner.session)
    }

    /** D4: ilk yükleme depo hatasıyla başarısız olursa hasSession true kalır ve resume tekrar dener. */
    @Test fun failedInitialLoadIsRetriedOnResume() = runBlocking {
        val inner = InMemorySessionStore()
        StoreBackedSessionManager(inner).saveSession(
            UserSession(accessToken = "stored-access", refreshToken = "stored-refresh", expiresIn = 3600, tokenType = "bearer", user = null),
        )
        var failReads = true
        val store = object : SessionStore by inner {
            override suspend fun readSupabaseSession(): String? {
                if (failReads) throw IOException("keystore temporarily unavailable")
                return inner.readSupabaseSession()
            }
        }
        val g = gateway(store)
        assertNull(g.accessTokenOrNull())
        assertTrue(g.hasSession.first())

        failReads = false
        g.resumeAutoRefresh()
        assertEquals("stored-access", g.accessTokenOrNull())
        assertTrue(g.hasSession.first())
    }
}
