package com.connect.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.connect.mobile.auth.AuthError
import com.connect.mobile.auth.BootstrapResult
import com.connect.mobile.auth.LoginResult
import com.connect.mobile.auth.LoginStep
import com.connect.mobile.auth.LoginUseCase
import com.connect.mobile.auth.LogoutUseCase
import com.connect.mobile.auth.SessionBootstrap
import com.connect.mobile.data.session.StoredGrant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration

sealed interface AuthUiState {
    data object Bootstrapping : AuthUiState
    data class LoginIdle(val error: AuthError? = null, val loggedOut: Boolean = false) : AuthUiState
    data object SigningIn : AuthUiState
    data object BindingSession : AuthUiState
    data object TearingDown : AuthUiState
    /** [expired]: the grant's expires_at has passed on the wall clock; only End Mission / Log Out remains. */
    data class MissionActive(val grant: StoredGrant, val expired: Boolean = false) : AuthUiState
    data object LoggingOut : AuthUiState
}

/** The password lives only here, in memory, and is cleared after every attempt. */
data class LoginForm(val email: String = "", val password: String = "")

/** State machine of design section 4.1. */
class RescuerAuthViewModel(
    private val login: LoginUseCase,
    private val logout: LogoutUseCase,
    bootstrap: SessionBootstrap,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Bootstrapping)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val _form = MutableStateFlow(LoginForm())
    val form: StateFlow<LoginForm> = _form.asStateFlow()

    private var expiryWatch: Job? = null

    init {
        viewModelScope.launch {
            val result = try {
                bootstrap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Store unreadable (I/O error): fail closed to the login screen instead of crashing.
                null
            }
            when (result) {
                is BootstrapResult.MissionActive -> showMission(result.grant)
                BootstrapResult.LoginRequired -> _state.value = AuthUiState.LoginIdle()
                null -> _state.value = AuthUiState.LoginIdle(error = AuthError.Server)
            }
        }
    }

    fun onEmailChange(value: String) = _form.update { it.copy(email = value) }

    fun onPasswordChange(value: String) = _form.update { it.copy(password = value) }

    /** Ignored outside LoginIdle, so double taps cannot start two logins. */
    fun submit() {
        if (_state.value !is AuthUiState.LoginIdle) return
        val attempt = _form.value
        _form.update { it.copy(password = "") }
        _state.value = AuthUiState.SigningIn
        viewModelScope.launch {
            val result = try {
                login(attempt.email, attempt.password) { step ->
                    _state.value = when (step) {
                        LoginStep.SigningIn -> AuthUiState.SigningIn
                        LoginStep.BindingSession -> AuthUiState.BindingSession
                        LoginStep.TearingDown -> AuthUiState.TearingDown
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LoginResult.Failure(AuthError.Server)
            }
            when (result) {
                is LoginResult.Success -> showMission(result.grant)
                is LoginResult.Failure -> _state.value = AuthUiState.LoginIdle(error = result.error)
            }
        }
    }

    /** "End Mission / Log Out" (after the confirm dialog). */
    fun endMission() {
        if (_state.value !is AuthUiState.MissionActive) return
        expiryWatch?.cancel()
        _state.value = AuthUiState.LoggingOut
        viewModelScope.launch {
            val wiped = try {
                logout(remote = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            // If the store could not be cleared, do not claim "logged out".
            _state.value = if (wiped) AuthUiState.LoginIdle(loggedOut = true) else AuthUiState.LoginIdle(error = AuthError.Server)
        }
    }

    /**
     * Shows the mission screen and flips it to the expired state once expires_at passes (review R2).
     * Wall clock only, re-checked at least every minute so a changed device clock is noticed too.
     * Nothing is deleted on expiry. TODO(OSP-73): monotonic clock + BiometricPrompt.
     */
    private fun showMission(grant: StoredGrant) {
        _state.value = AuthUiState.MissionActive(grant, expired = grant.isExpiredAt(clock.instant()))
        expiryWatch?.cancel()
        expiryWatch = viewModelScope.launch {
            while (true) {
                val now = clock.instant()
                if (grant.isExpiredAt(now)) break
                delay(Duration.between(now, grant.expiresAt).toMillis().coerceIn(1, EXPIRY_RECHECK_MS))
            }
            _state.update { if (it is AuthUiState.MissionActive && it.grant == grant) it.copy(expired = true) else it }
        }
    }

    private companion object {
        const val EXPIRY_RECHECK_MS = 60_000L
    }
}
