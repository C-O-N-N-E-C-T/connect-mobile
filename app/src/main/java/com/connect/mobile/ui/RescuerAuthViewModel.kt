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
import com.connect.mobile.auth.grant.AppLockState
import com.connect.mobile.auth.grant.GrantStatus
import com.connect.mobile.auth.grant.OfflineGrantChecker
import com.connect.mobile.data.session.StoredGrant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /**
     * [expired]: OfflineGrantPolicy says the offline window is over (wall clock, monotonic clock or
     * clock rollback). Nothing is deleted; the screen shows "Online Re-Authentication Required".
     */
    data class MissionActive(val grant: StoredGrant, val expired: Boolean = false) : AuthUiState
    data object LoggingOut : AuthUiState
}

/** The password lives only here, in memory, and is cleared after every attempt. */
data class LoginForm(val email: String = "", val password: String = "") {
    /** Never print the password (data-class toString would). */
    override fun toString(): String = "LoginForm(email=$email, password=<redacted>)"
}

/** State machine of design section 4.1, gated by OSP-73's OfflineGrantPolicy and AppLockState. */
class RescuerAuthViewModel(
    private val login: LoginUseCase,
    private val logout: LogoutUseCase,
    bootstrap: SessionBootstrap,
    private val grantChecker: OfflineGrantChecker,
    private val appLock: AppLockState,
) : ViewModel() {
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Bootstrapping)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val _form = MutableStateFlow(LoginForm())
    val form: StateFlow<LoginForm> = _form.asStateFlow()

    /** OSP-73: true iken Kurtarıcı Modu yerine kilit ekranı (BiometricGate) gösterilir. */
    val locked: StateFlow<Boolean> = appLock.locked

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

    /** OSP-73: yalnızca BiometricPrompt başarılı olunca çağrılır. */
    fun onUnlocked() = appLock.unlock()

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
                is LoginResult.Success -> {
                    // OSP-73: kullanıcı az önce şifresiyle çevrimiçi doğrulandı; ayrıca PIN/biyometri sorulmaz.
                    // Kilit, bir sonraki yeniden başlatma veya >15 dk arka planda yine devreye girer.
                    appLock.unlock()
                    showMission(result.grant)
                }
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
     * OSP-73: the policy decides (wall clock + monotonic clock + rollback), re-checked at least every
     * minute and exactly when the remaining time runs out. Nothing is deleted on expiry. A grant that
     * belongs to another device or role is wiped locally and the login screen is shown.
     */
    private fun showMission(grant: StoredGrant) {
        expiryWatch?.cancel()
        expiryWatch = viewModelScope.launch {
            var first = true
            while (true) {
                val status = try {
                    grantChecker.check(grant)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    GrantStatus.Expired(GrantStatus.Expired.Reason.STATE_UNAVAILABLE)
                }
                if (status is GrantStatus.Mismatch || status is GrantStatus.Missing) {
                    rejectGrant()
                    return@launch
                }
                val expired = status is GrantStatus.Expired
                if (first) {
                    _state.value = AuthUiState.MissionActive(grant, expired)
                    first = false
                } else {
                    _state.update { if (it is AuthUiState.MissionActive && it.grant == grant) it.copy(expired = expired) else it }
                }
                if (status is GrantStatus.Expired && status.reason.isFinal) break
                val wait = (status as? GrantStatus.Valid)?.remainingMillis ?: EXPIRY_RECHECK_MS
                delay(wait.coerceIn(1, EXPIRY_RECHECK_MS))
            }
        }
    }

    private suspend fun rejectGrant() {
        _state.value = AuthUiState.TearingDown
        try {
            logout(remote = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // LoginIdle is shown anyway; the grant gate already failed closed.
        }
        _state.value = AuthUiState.LoginIdle(error = AuthError.GrantInvalid)
    }

    private companion object {
        const val EXPIRY_RECHECK_MS = 60_000L
    }
}
