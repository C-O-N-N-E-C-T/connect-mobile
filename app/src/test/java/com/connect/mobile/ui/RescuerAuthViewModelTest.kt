package com.connect.mobile.ui

import com.connect.mobile.auth.AuthError
import com.connect.mobile.auth.Events
import com.connect.mobile.auth.FakeBackend
import com.connect.mobile.auth.FakeGateway
import com.connect.mobile.auth.InMemorySessionStore
import com.connect.mobile.auth.LoginUseCase
import com.connect.mobile.auth.LogoutUseCase
import com.connect.mobile.auth.SessionBootstrap
import com.connect.mobile.data.backend.BackendResult
import com.connect.mobile.data.backend.SessionResponse
import com.connect.mobile.data.device.DeviceIdProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

@OptIn(ExperimentalCoroutinesApi::class)
class RescuerAuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val events = Events()
    private val gateway = FakeGateway(events)
    private val backend = FakeBackend(events)
    private val store = InMemorySessionStore(events).apply { deviceId = FakeBackend.DEVICE_ID }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newViewModel(): RescuerAuthViewModel {
        val logout = LogoutUseCase(gateway, backend, store)
        val login = LoginUseCase(true, gateway, backend, store, DeviceIdProvider(store) { "Phone" }, logout)
        return RescuerAuthViewModel(login, logout, SessionBootstrap(store, logout)).also { advanceUntilIdle() }
    }

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
        advanceUntilIdle()
        assertTrue(vm.state.value is AuthUiState.MissionActive)

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
        val grant = com.connect.mobile.data.session.StoredGrant(
            "u1", FakeBackend.DEVICE_ID, "rescuer", java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(60),
        )
        store.writeGrant(grant)
        val vm = newViewModel()
        assertEquals(AuthUiState.MissionActive(grant), vm.state.value)
    }

    @Test fun endMissionIgnoredOutsideMission() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.endMission()
        assertEquals(AuthUiState.LoginIdle(), vm.state.value)
    }
}
