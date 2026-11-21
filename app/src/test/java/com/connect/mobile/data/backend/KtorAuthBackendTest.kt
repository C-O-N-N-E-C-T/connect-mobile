package com.connect.mobile.data.backend

import com.connect.mobile.data.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class KtorAuthBackendTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var bodies = mutableListOf<String>()

    private fun backend(handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        KtorAuthBackend(
            HttpClientFactory.create(MockEngine { req ->
                requests += req
                bodies += String(req.body.toByteArray())
                handler(req)
            }),
            "https://api.example.test/",
        )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private val rescuerJson = """
        {"user_id":"u1","role":"rescuer","device_id":"d1","extra":1,
         "offline_mission_grant":{"user_id":"u1","device_id":"d1",
           "authenticated_at":"2026-11-19T10:00:00+00:00","expires_at":"2026-11-22T10:00:00+00:00"}}
    """.trimIndent()

    @Test fun bindSessionSendsPathBearerAndJsonBody() = runTest {
        val r = backend { json(rescuerJson) }.bindSession("test-token", "d1", "Pixel Test")
        val req = requests.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("https://api.example.test/api/v1/auth/session", req.url.toString())
        assertEquals("Bearer test-token", req.headers[HttpHeaders.Authorization])
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals("d1", body["device_id"]!!.jsonPrimitive.content)
        assertEquals("Pixel Test", body["device_name"]!!.jsonPrimitive.content)
        val ok = r as BackendResult.Ok
        assertEquals("rescuer", ok.value.role)
        assertEquals("2026-11-22T10:00:00+00:00", ok.value.grant!!.expiresAt)
    }

    @Test fun coordinatorWithoutGrantParsed() = runTest {
        val r = backend { json("""{"user_id":"u2","role":"coordinator","device_id":"d1"}""") }
            .bindSession("t", "d1", "n") as BackendResult.Ok
        assertEquals("coordinator", r.value.role)
        assertNull(r.value.grant)
    }

    @Test fun everyErrorCodeIsParsed() = runTest {
        val cases = listOf(
            401 to "UNAUTHENTICATED", 403 to "ROLE_FORBIDDEN", 403 to "ACCOUNT_DISABLED",
            403 to "DEVICE_REVOKED", 403 to "SESSION_NOT_BOUND", 429 to "RATE_LIMITED",
            400 to "VALIDATION_ERROR", 404 to "NOT_FOUND", 500 to "INTERNAL_ERROR",
        )
        for ((status, code) in cases) {
            val r = backend {
                json("""{"error_code":"$code","message":"m"}""", HttpStatusCode.fromValue(status))
            }.bindSession("t", "d", "n")
            assertEquals(BackendResult.Err(BackendError.Http(status, code, "m")), r)
        }
    }

    @Test fun nonJsonErrorBodyGivesHttpWithoutCode() = runTest {
        val r = backend { respond("<html>bad gateway</html>", HttpStatusCode.BadGateway) }.bindSession("t", "d", "n")
        assertEquals(BackendResult.Err(BackendError.Http(502, null, null)), r)
    }

    @Test fun unparsableSuccessBodyIsMalformed() = runTest {
        val r = backend { json("""{"role":"rescuer"}""") }.bindSession("t", "d", "n")
        assertEquals(BackendResult.Err(BackendError.Malformed), r)
    }

    @Test fun ioExceptionIsNetwork() = runTest {
        val r = backend { throw IOException("CLEARTEXT communication not permitted") }.bindSession("t", "d", "n")
        assertEquals(BackendResult.Err(BackendError.Network), r)
    }

    /** R12: Ktor's HttpRequestTimeoutException (an IOException) maps to Network. Real time: MockEngine has its own dispatcher. */
    @Test fun requestTimeoutIsNetwork() = runBlocking {
        val slow = KtorAuthBackend(
            HttpClientFactory.create(MockEngine { delay(10_000); respond("{}") }, requestTimeoutMillis = 100),
            "https://api.example.test",
        )
        val r = withTimeout(5_000) { slow.bindSession("t", "d", "n") }
        assertEquals(BackendResult.Err(BackendError.Network), r)
    }

    @Test fun logoutSendsBearerAndNoBody() = runTest {
        val r = backend { respond("", HttpStatusCode.NoContent) }.logout("test-token")
        assertEquals(BackendResult.Ok(Unit), r)
        val req = requests.single()
        assertEquals("https://api.example.test/api/v1/auth/logout", req.url.toString())
        assertEquals("Bearer test-token", req.headers[HttpHeaders.Authorization])
        assertTrue(bodies.single().isEmpty())
    }

    @Test fun logoutErrorIsReported() = runTest {
        val r = backend { json("""{"error_code":"UNAUTHENTICATED","message":"x"}""", HttpStatusCode.Unauthorized) }
            .logout("t")
        assertNotNull(r as? BackendResult.Err)
    }
}
