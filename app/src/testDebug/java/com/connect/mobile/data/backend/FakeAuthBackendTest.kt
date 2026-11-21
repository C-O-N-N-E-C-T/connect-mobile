package com.connect.mobile.data.backend

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64

class FakeAuthBackendTest {
    private val now = Instant.parse("2026-11-19T10:00:00Z")
    private val fake = FakeAuthBackend(now = { now }, delayMs = 0)

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{\"alg\":\"none\"}".toByteArray()) + "." +
            enc.encodeToString(payload.toByteArray()) + ".sig"
    }

    @Test fun rescuerGets72hGrant() = runTest {
        val r = fake.bindSession(jwt("""{"sub":"u1","user_role":"rescuer"}"""), "d1", "n") as BackendResult.Ok
        val g = r.value.grant!!
        assertEquals("u1", g.userId)
        assertEquals("d1", g.deviceId)
        val issued = OffsetDateTime.parse(g.authenticatedAt).toInstant()
        val expires = OffsetDateTime.parse(g.expiresAt).toInstant()
        assertEquals(now, issued)
        assertEquals(Duration.ofHours(72), Duration.between(issued, expires))
    }

    @Test fun coordinatorGetsNoGrant() = runTest {
        val r = fake.bindSession(jwt("""{"sub":"u2","user_role":"coordinator"}"""), "d1", "n") as BackendResult.Ok
        assertEquals("coordinator", r.value.role)
        assertNull(r.value.grant)
    }

    @Test fun nullRoleIsAccountDisabled() = runTest {
        val r = fake.bindSession(jwt("""{"sub":"u3","user_role":null}"""), "d1", "n")
        assertEquals(403, ((r as BackendResult.Err).error as BackendError.Http).status)
        assertEquals("ACCOUNT_DISABLED", (r.error as BackendError.Http).code)
    }
}
