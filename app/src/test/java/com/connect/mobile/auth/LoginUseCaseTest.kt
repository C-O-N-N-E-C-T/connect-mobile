package com.connect.mobile.auth

import com.connect.mobile.data.backend.BackendError
import com.connect.mobile.data.backend.BackendResult
import com.connect.mobile.data.device.DeviceIdProvider
import com.connect.mobile.data.supabase.SignInOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginUseCaseTest {
    private val events = Events()
    private val gateway = FakeGateway(events)
    private val backend = FakeBackend(events)
    private val store = InMemorySessionStore(events).apply { deviceId = FakeBackend.DEVICE_ID }
    private val logout = LogoutUseCase(gateway, backend, store)

    private fun useCase(configured: Boolean = true) = LoginUseCase(
        isConfigured = configured,
        gateway = gateway,
        backend = backend,
        store = store,
        deviceIdProvider = DeviceIdProvider(store) { "Test Phone" },
        logout = logout,
    )

    private val fullTeardown = listOf("clearGrant", "backendLogout", "signOut", "clearLocalSession", "clearAuth")

    @Test fun rescuerStoresGrantAndSendsDeviceInfo() = runTest {
        val r = useCase()("rescuer@connect.internal", "pw") as LoginResult.Success
        assertEquals("rescuer", r.grant.role)
        assertEquals(r.grant, store.readGrant())
        assertEquals(FakeBackend.DEVICE_ID, backend.lastDeviceId)
        assertEquals("Test Phone", backend.lastDeviceName)
        assertEquals(listOf("signIn", "bindSession"), events)
    }

    @Test fun coordinatorAndAdministratorAreRejectedWithFullTeardown() = runTest {
        for (role in listOf("coordinator", "administrator")) {
            events.clear()
            backend.bind = { BackendResult.Ok(FakeBackend.rescuerResponse(role = role, withGrant = false)) }
            val r = useCase()("x@connect.internal", "pw")
            assertEquals(LoginResult.Failure(AuthError.RoleNotAllowed), r)
            assertEquals(listOf("signIn", "bindSession") + fullTeardown, events)
            assertNull(store.readGrant())
            assertNull(store.session)
        }
    }

    @Test fun roleForbiddenIsRoleNotAllowedWithTeardown() = runTest {
        backend.bind = { BackendResult.Err(BackendError.Http(403, "ROLE_FORBIDDEN", "m")) }
        assertEquals(LoginResult.Failure(AuthError.RoleNotAllowed), useCase()("x@y.io", "pw"))
        assertEquals(listOf("signIn", "bindSession") + fullTeardown, events)
    }

    @Test fun backendErrorsAreMappedWithTeardown() = runTest {
        val cases = listOf(
            BackendError.Http(401, "UNAUTHENTICATED", null) to AuthError.SessionRejected,
            BackendError.Http(403, "ACCOUNT_DISABLED", null) to AuthError.AccountDisabled,
            BackendError.Http(403, "DEVICE_REVOKED", null) to AuthError.DeviceRevoked,
            BackendError.Http(403, "SESSION_NOT_BOUND", null) to AuthError.Server,
            BackendError.Http(429, "RATE_LIMITED", null) to AuthError.RateLimited,
            BackendError.Http(429, null, null) to AuthError.RateLimited,
            BackendError.Http(400, "VALIDATION_ERROR", null) to AuthError.RequestRejected,
            BackendError.Http(404, "NOT_FOUND", null) to AuthError.Server,
            BackendError.Http(500, "INTERNAL_ERROR", null) to AuthError.Server,
            BackendError.Http(503, null, null) to AuthError.Server,
            BackendError.Network to AuthError.Network,
            BackendError.Malformed to AuthError.GrantInvalid,
        )
        for ((backendError, expected) in cases) {
            events.clear()
            backend.bind = { BackendResult.Err(backendError) }
            assertEquals(backendError.toString(), LoginResult.Failure(expected), useCase()("x@y.io", "pw"))
            assertEquals(backendError.toString(), listOf("signIn", "bindSession") + fullTeardown, events)
        }
    }

    @Test fun invalidGrantsAreRejected() = runTest {
        val bad = listOf(
            FakeBackend.rescuerResponse(withGrant = false),
            FakeBackend.rescuerResponse(expiresAt = "2026-11-22T10:01:01Z"), // 72 h + 61 s
            FakeBackend.rescuerResponse(expiresAt = "2026-11-19T10:00:00Z"), // zero length
            FakeBackend.rescuerResponse(expiresAt = "2026-11-18T10:00:00Z"), // negative
            FakeBackend.rescuerResponse(expiresAt = "2026-11-22 10:00"), // not ISO-8601 with offset
            FakeBackend.rescuerResponse(grantUserId = "someone-else"),
            FakeBackend.rescuerResponse(grantDeviceId = "other-device"),
        )
        for (response in bad) {
            events.clear()
            backend.bind = { BackendResult.Ok(response) }
            assertEquals(response.toString(), LoginResult.Failure(AuthError.GrantInvalid), useCase()("x@y.io", "pw"))
            assertEquals(listOf("signIn", "bindSession") + fullTeardown, events)
            assertNull(store.readGrant())
        }
    }

    @Test fun grantUpTo72hPlus60sIsAccepted() = runTest {
        backend.bind = { BackendResult.Ok(FakeBackend.rescuerResponse(expiresAt = "2026-11-22T10:01:00Z")) }
        assertTrue(useCase()("x@y.io", "pw") is LoginResult.Success)
    }

    @Test fun badCredentialsStoreNothingAndSkipBackend() = runTest {
        gateway.outcome = SignInOutcome.Failure(AuthError.BadCredentials)
        assertEquals(LoginResult.Failure(AuthError.BadCredentials), useCase()("x@y.io", "pw"))
        assertEquals(listOf("signIn", "clearGrant", "clearLocalSession", "clearAuth"), events)
        assertNull(store.readGrant())
    }

    @Test fun invalidInputDoesNothing() = runTest {
        for ((e, p) in listOf("" to "pw", "x@y.io" to "", "not-an-email" to "pw")) {
            assertEquals(LoginResult.Failure(AuthError.InvalidInput), useCase()(e, p))
        }
        assertTrue(events.isEmpty())
    }

    @Test fun notConfigured() = runTest {
        assertEquals(LoginResult.Failure(AuthError.NotConfigured), useCase(configured = false)("x@y.io", "pw"))
        assertTrue(events.isEmpty())
    }

    @Test fun reportsSteps() = runTest {
        val steps = mutableListOf<LoginStep>()
        backend.bind = { BackendResult.Err(BackendError.Network) }
        useCase()("x@y.io", "pw") { steps += it }
        assertEquals(listOf(LoginStep.SigningIn, LoginStep.BindingSession, LoginStep.TearingDown), steps)
        assertNotNull(store.deviceId)
    }
}
