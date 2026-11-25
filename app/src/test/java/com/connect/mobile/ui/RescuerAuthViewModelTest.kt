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
import com.connect.mobile.auth.grant.AppLockState
import com.connect.mobile.auth.grant.FakeGrantAnchorStore
import com.connect.mobile.auth.grant.OfflineGrantChecker
import com.connect.mobile.auth.grant.OfflineGrantPolicy
import com.connect.mobile.auth.grant.SchedulerMonotonicClock
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
import org.junit.Assert.assertFalse
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
    private val anchors = FakeGrantAnchorStore()
    private lateinit var appLock: AppLockState

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /**
     * runCurrent, not advanceUntilIdle: in MissionActive the expiry watcher would otherwise run the
     * virtual clock up to expires_at.
     */
    private fun TestScope.newViewModel(): RescuerAuthViewModel {
        val clock = SchedulerClock(testScheduler)
        val mono = SchedulerMonotonicClock(testScheduler)
        val deviceIds = DeviceIdProvider(store) { "Phone" }
        val logout = LogoutUseCase(gateway, backend, store, grantAnchors = anchors)
        val login = LoginUseCase(true, gateway, backend, store, deviceIds, logout, clock)
        val bootstrap = SessionBootstrap(store, deviceIds, logout)
        val checker = OfflineGrantChecker(OfflineGrantPolicy(), anchors, deviceIds, clock, mono)
        appLock = AppLockState(mono)
        return RescuerAuthViewModel(login, logout, bootstrap, checker, appLock).also { runCurrent() }
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

    @Test fun loginFormToStringHidesPassword() {
        val text = LoginForm(email = "r@connect.internal", password = "secret-pw").toString()
        assertTrue(text, "secret-pw" !in text)
    }

    @Test fun endMissionIgnoredOutsideMission() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.endMission()
        assertEquals(AuthUiState.LoginIdle(), vm.state.value)
    }

    /** OSP-73 S7: another device's grant is wiped locally, login is shown; no victim data is involved. */
    @Test fun grantForAnotherDeviceIsWipedToLogin() = runTest(dispatcher) {
        store.writeGrant(grant(TEST_NOW.plusSeconds(3600)).copy(deviceId = "other-device"))
        val vm = newViewModel()
        advanceUntilIdle()
        assertEquals(AuthUiState.LoginIdle(error = AuthError.GrantInvalid), vm.state.value)
        assertNull(store.readGrant())
    }

    /** OSP-73 S3: the mission stays open after the 1 h access token would have expired. */
    @Test fun missionStaysOpenAfterAccessTokenHour() = runTest(dispatcher) {
        val g = grant(TEST_NOW.plusSeconds(72 * 3600))
        store.writeGrant(g)
        val vm = newViewModel()
        advanceTimeBy(2 * 3600_000L)
        assertEquals(AuthUiState.MissionActive(g, expired = false), vm.state.value)
    }

    /** OSP-73 S10: a fresh process starts locked; only a successful prompt unlocks. */
    @Test fun startsLockedAndUnlocks() = runTest(dispatcher) {
        val vm = newViewModel()
        assertTrue(vm.locked.value)
        vm.onUnlocked()
        assertFalse(vm.locked.value)
    }

    /** OSP-73: the anchor is written while the mission is open and removed on logout. */
    @Test fun anchorWrittenAndClearedOnLogout() = runTest(dispatcher) {
        store.writeGrant(grant(TEST_NOW.plusSeconds(3600)))
        val vm = newViewModel()
        assertTrue(anchors.writes > 0)
        vm.endMission()
        advanceUntilIdle()
        assertNull(anchors.anchor)
    }

    /** OSP-73: a successful online login just verified the user, so no extra biometric prompt. */
    @Test fun successfulLoginUnlocks() = runTest(dispatcher) {
        val vm = newViewModel()
        assertTrue(vm.locked.value)
        vm.onEmailChange("rescuer@connect.internal")
        vm.onPasswordChange("secret")
        vm.submit()
        runCurrent()
        assertTrue(vm.state.value is AuthUiState.MissionActive)
        assertFalse(vm.locked.value)
    }

    /** OSP-73: a failed login does not open the lock. */
    @Test fun failedLoginKeepsLock() = runTest(dispatcher) {
        backend.bind = { BackendResult.Ok(FakeBackend.rescuerResponse(role = "coordinator", withGrant = false)) }
        val vm = newViewModel()
        vm.onEmailChange("coordinator@connect.internal")
        vm.onPasswordChange("pw")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.locked.value)
    }
}
