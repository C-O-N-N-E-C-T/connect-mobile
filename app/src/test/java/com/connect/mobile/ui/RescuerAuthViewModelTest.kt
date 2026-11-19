package com.connect.mobile.ui

import com.connect.mobile.auth.AuthError
import com.connect.mobile.auth.Events
import com.connect.mobile.auth.FakeBackend
import com.connect.mobile.auth.FakeGateway
import com.connect.mobile.auth.InMemorySessionStore
import com.connect.mobile.auth.LoginUseCase
import com.connect.mobile.auth.LogoutUseCase
import com.connect.mobile.auth.SchedulerClock
import com.connect.mobile.auth.SessionBootstrap
import com.connect.mobile.auth.TEST_NOW
import com.connect.mobile.data.backend.BackendResult
import com.connect.mobile.data.backend.SessionResponse
import com.connect.mobile.data.device.DeviceIdProvider
import com.connect.mobile.data.session.StoredGrant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RescuerAuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val events = Events()
    private val gateway = FakeGateway(events)
    private val backend = FakeBackend(events)
    private val store = InMemorySessionStore(events).apply { deviceId = FakeBackend.DEVICE_ID }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /**
     * runCurrent, not advanceUntilIdle: in MissionActive the expiry watcher would otherwise run the
     * virtual clock up to expires_at.
     */
    private fun TestScope.newViewModel(): RescuerAuthViewModel {
        val clock = SchedulerClock(testScheduler)
        val logout = LogoutUseCase(gateway, backend, store)
        val login = LoginUseCase(true, gateway, backend, store, DeviceIdProvider(store) { "Phone" }, logout, clock)
        val bootstrap = SessionBootstrap(store, DeviceIdProvider(store) { "Phone" }, logout)
        return RescuerAuthViewModel(login, logout, bootstrap, clock).also { runCurrent() }
    }

    private fun grant(expiresAt: Instant) = StoredGrant("u1", FakeBackend.DEVICE_ID, "rescuer", TEST_NOW.minusSeconds(60), expiresAt)

    @Test fun loginLogoutHappyPath() = runTest(dispatcher) {
        val bindGate = CompletableDeferred<BackendResult<SessionResponse>>()
        backend.bind = { bindGate.await() }
        val vm = newViewModel()
        assertEquals(AuthUiState.LoginIdle(), vm.state.value)

        vm.onEmailChange("rescuer@connect.internal")
        vm.onPasswordChange("secret")
        vm.submit()
        assertEquals(AuthUiState.SigningIn, vm.state.value)
        assertEquals("", vm.form.value.password) // cleared immediately
        assertEquals("rescuer@connect.internal", vm.form.value.email)
        runCurrent()
        assertEquals(AuthUiState.BindingSession, vm.state.value)

        bindGate.complete(BackendResult.Ok(FakeBackend.rescuerResponse()))
        runCurrent()
        val mission = vm.state.value as AuthUiState.MissionActive
        assertEquals(false, mission.expired)

        vm.endMission()
        assertEquals(AuthUiState.LoggingOut, vm.state.value)
        advanceUntilIdle()
        assertEquals(AuthUiState.LoginIdle(loggedOut = true), vm.state.value)
        assertNull(store.readGrant())
    }

    @Test fun roleRejectionEndsInLoginIdleWithError() = runTest(dispatcher) {
        backend.bind = { BackendResult.Ok(FakeBackend.rescuerResponse(role = "coordinator", withGrant = false)) }
        val vm = newViewModel()
        vm.onEmailChange("coordinator@connect.internal")
        vm.onPasswordChange("pw")
        vm.submit()
        advanceUntilIdle()
        assertEquals(AuthUiState.LoginIdle(error = AuthError.RoleNotAllowed), vm.state.value)
        assertTrue(events.contains("clearAuth"))
    }

    @Test fun doubleSubmitIsIgnored() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("r@connect.internal")
        vm.onPasswordChange("pw")
        vm.submit()
        vm.submit()
        advanceUntilIdle()
        assertEquals(1, gateway.signInCalls)
    }

    @Test fun startsInMissionWhenGrantStored() = runTest(dispatcher) {
        val g = grant(TEST_NOW.plusSeconds(3600))
        store.writeGrant(g)
        val vm = newViewModel()
        assertEquals(AuthUiState.MissionActive(g, expired = false), vm.state.value)
    }

    /** R2: an expired grant at start-up shows the expired state; logout stays available; nothing is deleted. */
    @Test fun expiredGrantAtStartShowsExpiredState() = runTest(dispatcher) {
        val g = grant(TEST_NOW.minusSeconds(1))
        store.writeGrant(g)
        val vm = newViewModel()
        assertEquals(AuthUiState.MissionActive(g, expired = true), vm.state.value)
        assertEquals(g, store.readGrant())
        assertTrue(events.isEmpty())
        vm.endMission()
        advanceUntilIdle()
        assertEquals(AuthUiState.LoginIdle(loggedOut = true), vm.state.value)
        assertNull(store.readGrant())
    }

    /** R2: the open mission screen flips to expired when expires_at passes, without deleting anything. */
    @Test fun missionFlipsToExpiredWhileOpen() = runTest(dispatcher) {
        val g = grant(TEST_NOW.plusSeconds(90))
        store.writeGrant(g)
        val vm = newViewModel()
        advanceTimeBy(89_000)
        assertEquals(AuthUiState.MissionActive(g, expired = false), vm.state.value)
        advanceTimeBy(1_001)
        assertEquals(AuthUiState.MissionActive(g, expired = true), vm.state.value)
        assertEquals(g, store.readGrant())
        assertTrue(events.isEmpty())
    }

    /** R4: an unreadable store at start-up fails closed to the login screen instead of crashing. */
    @Test fun bootstrapStoreErrorShowsLoginWithError() = runTest(dispatcher) {
        store.readError = java.io.IOException("unreadable")
        val vm = newViewModel()
        assertEquals(AuthUiState.LoginIdle(error = AuthError.Server), vm.state.value)
    }

    /** R4: if the store cannot be cleared, the UI must not claim "logged out". */
    @Test fun failedWipeIsNotReportedAsLoggedOut() = runTest(dispatcher) {
        store.writeGrant(grant(TEST_NOW.plusSeconds(3600)))
        store.clearAuthError = java.io.IOException("disk error")
        val vm = newViewModel()
        vm.endMission()
        advanceUntilIdle()
        assertEquals(AuthUiState.LoginIdle(error = AuthError.Server), vm.state.value)
    }

    @Test fun endMissionIgnoredOutsideMission() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.endMission()
        assertEquals(AuthUiState.LoginIdle(), vm.state.value)
    }
}
