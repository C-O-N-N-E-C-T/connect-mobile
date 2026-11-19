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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    data object Bootstrapping : AuthUiState
    data class LoginIdle(val error: AuthError? = null, val loggedOut: Boolean = false) : AuthUiState
    data object SigningIn : AuthUiState
    data object BindingSession : AuthUiState
    data object TearingDown : AuthUiState
    data class MissionActive(val grant: StoredGrant) : AuthUiState
    data object LoggingOut : AuthUiState
}

/** The password lives only here, in memory, and is cleared after every attempt. */
data class LoginForm(val email: String = "", val password: String = "")

/** State machine of design section 4.1. */
class RescuerAuthViewModel(
    private val login: LoginUseCase,
    private val logout: LogoutUseCase,
    bootstrap: SessionBootstrap,
) : ViewModel() {
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Bootstrapping)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val _form = MutableStateFlow(LoginForm())
    val form: StateFlow<LoginForm> = _form.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                when (val r = bootstrap()) {
                    is BootstrapResult.MissionActive -> AuthUiState.MissionActive(r.grant)
                    BootstrapResult.LoginRequired -> AuthUiState.LoginIdle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Store unreadable (I/O error): fail closed to the login screen instead of crashing.
                AuthUiState.LoginIdle(error = AuthError.Server)
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
            _state.value = when (result) {
                is LoginResult.Success -> AuthUiState.MissionActive(result.grant)
                is LoginResult.Failure -> AuthUiState.LoginIdle(error = result.error)
            }
        }
    }

    /** "End Mission / Log Out" (after the confirm dialog). */
    fun endMission() {
        if (_state.value !is AuthUiState.MissionActive) return
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
}
