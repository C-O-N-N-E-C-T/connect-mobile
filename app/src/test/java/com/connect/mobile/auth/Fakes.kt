package com.connect.mobile.auth

import com.connect.mobile.data.backend.AuthBackend
import com.connect.mobile.data.backend.BackendResult
import com.connect.mobile.data.backend.OfflineMissionGrantDto
import com.connect.mobile.data.backend.SessionResponse
import com.connect.mobile.data.session.SessionStore
import com.connect.mobile.data.session.StoredGrant
import com.connect.mobile.data.supabase.SignInOutcome
import com.connect.mobile.data.supabase.SupabaseAuthGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class Events : ArrayList<String>()

class FakeGateway(private val events: Events) : SupabaseAuthGateway {
    var outcome: SignInOutcome = SignInOutcome.Success
    var token: String? = "test-token"
    var signOutError: Exception? = null
    var initDelayMs = 0L
    var initialized = false
    var signInCalls = 0
    var clearedAfterInit: Boolean? = null
    var clearError: Throwable? = null

    override suspend fun awaitInitialized() {
        if (!initialized) {
            delay(initDelayMs)
            initialized = true
        }
    }

    override suspend fun signIn(email: String, password: String): SignInOutcome {
        awaitInitialized()
        signInCalls++
        events += "signIn"
        return outcome
    }

    override suspend fun accessTokenOrNull(): String? {
        awaitInitialized()
        return token
    }

    override suspend fun signOut() {
        events += "signOut"
        signOutError?.let { throw it }
    }

    override suspend fun clearLocalSession() {
        clearedAfterInit = initialized
        events += "clearLocalSession"
        clearError?.let { throw it }
    }
}

class FakeBackend(private val events: Events) : AuthBackend {
    var bind: suspend () -> BackendResult<SessionResponse> = { BackendResult.Ok(rescuerResponse()) }
    var logoutBehavior: suspend () -> BackendResult<Unit> = { BackendResult.Ok(Unit) }
    var lastDeviceId: String? = null
    var lastDeviceName: String? = null

    override suspend fun bindSession(accessToken: String, deviceId: String, deviceName: String): BackendResult<SessionResponse> {
        events += "bindSession"
        lastDeviceId = deviceId
        lastDeviceName = deviceName
        return bind()
    }

    override suspend fun logout(accessToken: String): BackendResult<Unit> {
        events += "backendLogout"
        return logoutBehavior()
    }

    companion object {
        const val DEVICE_ID = "11111111-1111-4111-8111-111111111111"

        fun rescuerResponse(
            role: String = "rescuer",
            authenticatedAt: String = "2026-11-19T10:00:00Z",
            expiresAt: String = "2026-11-22T10:00:00+00:00",
            grantUserId: String = "u1",
            grantDeviceId: String = DEVICE_ID,
            withGrant: Boolean = true,
        ) = SessionResponse(
            userId = "u1",
            role = role,
            deviceId = DEVICE_ID,
            grant = if (withGrant) OfflineMissionGrantDto(grantUserId, grantDeviceId, authenticatedAt, expiresAt) else null,
        )
    }
}

class InMemorySessionStore(private val events: Events = Events()) : SessionStore {
    var deviceId: String? = null
    var session: String? = null
    private val grantState = MutableStateFlow<StoredGrant?>(null)

    override suspend fun getOrCreateDeviceId(generate: () -> String): String = deviceId ?: generate().also { deviceId = it }
    override suspend fun readSupabaseSession() = session
    override suspend fun writeSupabaseSession(json: String) { session = json }
    override suspend fun deleteSupabaseSession() { session = null }
    override suspend fun readGrant() = grantState.value
    override suspend fun writeGrant(grant: StoredGrant) { grantState.value = grant }
    override suspend fun clearGrant() {
        events += "clearGrant"
        grantState.value = null
    }
    override suspend fun clearAuth() {
        events += "clearAuth"
        session = null
        grantState.value = null
    }
    override val grant: Flow<StoredGrant?> = grantState
}

/** Records clearGrant / clearAuth on top of a real store. */
class RecordingSessionStore(private val delegate: SessionStore, private val events: Events) : SessionStore by delegate {
    override suspend fun clearGrant() {
        events += "clearGrant"
        delegate.clearGrant()
    }

    override suspend fun clearAuth() {
        events += "clearAuth"
        delegate.clearAuth()
    }
}
