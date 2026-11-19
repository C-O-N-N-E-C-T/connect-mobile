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
            _state.value = when (val r = bootstrap()) {
                is BootstrapResult.MissionActive -> AuthUiState.MissionActive(r.grant)
                BootstrapResult.LoginRequired -> AuthUiState.LoginIdle()
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
            val result = login(attempt.email, attempt.password) { step ->
                _state.value = when (step) {
                    LoginStep.SigningIn -> AuthUiState.SigningIn
                    LoginStep.BindingSession -> AuthUiState.BindingSession
                    LoginStep.TearingDown -> AuthUiState.TearingDown
                }
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
            logout(remote = true)
            _state.value = AuthUiState.LoginIdle(loggedOut = true)
        }
    }
}
