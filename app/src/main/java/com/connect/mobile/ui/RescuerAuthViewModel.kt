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
import com.connect.mobile.auth.grant.Access
import com.connect.mobile.auth.grant.AppLockState
import com.connect.mobile.auth.grant.Capability
import com.connect.mobile.auth.grant.GrantStatus
import com.connect.mobile.auth.grant.OfflineGrantChecker
import com.connect.mobile.data.session.StoredGrant
import com.connect.mobile.sync.SyncBackoff
import com.connect.mobile.sync.SyncOutcome
import com.connect.mobile.sync.SyncRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    data object Bootstrapping : AuthUiState
    /** [pendingUploads] (OSP-76): queued records that will be uploaded after this sign-in. */
    data class LoginIdle(
        val error: AuthError? = null,
        val loggedOut: Boolean = false,
        val pendingUploads: Int = 0,
    ) : AuthUiState
    data object SigningIn : AuthUiState
    data object BindingSession : AuthUiState
    data object TearingDown : AuthUiState
    /**
     * [expired]: OfflineGrantPolicy says the offline window is over (wall clock, monotonic clock or
     * clock rollback). Nothing is deleted; the screen shows "Online Re-Authentication Required".
     * [sync] (OSP-76): state of the reconnection upload gate.
     */
    data class MissionActive(
        val grant: StoredGrant,
        val expired: Boolean = false,
        val sync: SyncBanner = SyncBanner.None,
    ) : AuthUiState
    data object LoggingOut : AuthUiState

    /**
     * OSP-76 step 5: 403 DEVICE_REVOKED / ACCOUNT_DISABLED during upload. Rescuer Mode and PII are
     * locked; tokens and the grant are gone, the [pendingUploads] queued records are kept encrypted.
     */
    data class Locked(val reason: AuthError, val pendingUploads: Int) : AuthUiState
}

/** OSP-76: what the mission screen shows about queued uploads. */
sealed interface SyncBanner {
    data object None : SyncBanner
    /** Transient failure (step 2): records kept, retried with backoff while online. */
    data class Waiting(val pending: Int) : SyncBanner
    /** Refresh refused / 401 (step 3): records kept, an online sign-in is needed to upload them. */
    data class SignInRequired(val pending: Int) : SyncBanner
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
    /** OSP-76 reconnection gate; null = no upload gate (e.g. tests of the login flow only). */
    private val sync: SyncRunner? = null,
    /** OSP-76: connectivity; the gate runs each time this becomes true while a mission is active. */
    private val online: StateFlow<Boolean> = MutableStateFlow(false),
    private val backoff: SyncBackoff = SyncBackoff(),
) : ViewModel() {
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Bootstrapping)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val _form = MutableStateFlow(LoginForm())
    val form: StateFlow<LoginForm> = _form.asStateFlow()

    /** OSP-73: true iken Kurtarıcı Modu yerine kilit ekranı (BiometricGate) gösterilir. */
    val locked: StateFlow<Boolean> = appLock.locked

    private var expiryWatch: Job? = null
    private var syncJob: Job? = null

    /**
     * OSP-73 x OSP-76: cloud sync is allowed only while the grant is Valid (Capability.CLOUD_SYNC is
     * locked on expiry). Set by the expiry watch after each policy check; the sync gate waits for it.
     */
    private val grantAllowsSync = MutableStateFlow(false)

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

    /**
     * OSP-73: kilit ekranından şifreyle çevrimiçi giriş (biyometri / ekran kilidi kullanılamıyorsa).
     * Kilit AÇILMAZ ve grant SİLİNMEZ: yalnızca başarılı giriş kilidi açar; giriş olmazsa yeniden
     * başlatma kilit ekranına geri döner. Kuyruktaki kayıtların sayısı giriş ekranında gösterilir.
     */
    fun signInFromLock() {
        val mission = _state.value as? AuthUiState.MissionActive ?: return
        if (!appLock.locked.value) return
        val pending = (mission.sync as? SyncBanner.SignInRequired)?.pending
            ?: (mission.sync as? SyncBanner.Waiting)?.pending
            ?: 0
        stopMissionJobs()
        _state.value = AuthUiState.LoginIdle(pendingUploads = pending)
    }

    /** Ignored outside LoginIdle, so double taps cannot start two logins. */
    fun submit() {
        val idle = _state.value as? AuthUiState.LoginIdle ?: return
        val pendingUploads = idle.pendingUploads
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
                    // OSP-76: a successful sign-in restarts the upload gate (records upload after re-authentication).
                    showMission(result.grant)
                }
                // The queued records are still on the device; keep telling the rescuer about them.
                is LoginResult.Failure ->
                    _state.value = AuthUiState.LoginIdle(error = result.error, pendingUploads = pendingUploads)
            }
        }
    }

    /**
     * OSP-76 step 3: the rescuer chose to sign in online so queued records can be uploaded. Nothing is
     * deleted here; the grant stays, so a restart without signing in still reaches the mission screen.
     */
    fun signInToUpload() {
        val mission = _state.value as? AuthUiState.MissionActive ?: return
        val banner = mission.sync as? SyncBanner.SignInRequired ?: return
        stopMissionJobs()
        _state.value = AuthUiState.LoginIdle(pendingUploads = banner.pending)
    }

    /** OSP-76 step 5: from the lock screen, sign in again (e.g. after the account was reactivated). */
    fun leaveLock() {
        val locked = _state.value as? AuthUiState.Locked ?: return
        _state.value = AuthUiState.LoginIdle(pendingUploads = locked.pendingUploads)
    }

    /** "End Mission / Log Out" (after the confirm dialog). */
    fun endMission() {
        if (_state.value !is AuthUiState.MissionActive) return
        stopMissionJobs()
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
        grantAllowsSync.value = false
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
                // After the state update, so a banner from the gate always lands on this MissionActive.
                grantAllowsSync.value = grantChecker.access(Capability.CLOUD_SYNC, status) == Access.Allowed
                if (status is GrantStatus.Expired && status.reason.isFinal) break
                val wait = (status as? GrantStatus.Valid)?.remainingMillis ?: EXPIRY_RECHECK_MS
                delay(wait.coerceIn(1, EXPIRY_RECHECK_MS))
            }
        }
        startSyncGate()
    }

    /**
     * OSP-76: every time connectivity returns during a mission, run the reconnection gate. Going
     * offline cancels a pass (collectLatest); transient failures are retried with backoff while online.
     * OSP-73: the gate also needs a Valid grant; when the offline window ends a running pass is cancelled.
     */
    private fun startSyncGate() {
        val runner = sync ?: return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            // OSP-73: no upload with an expired / unverifiable grant ("Online Re-Authentication Required").
            combine(online, grantAllowsSync) { isOnline, allowed -> isOnline && allowed }
                .distinctUntilChanged()
                .collectLatest { canSync -> if (canSync) syncWhileOnline(runner) }
        }
    }

    private suspend fun syncWhileOnline(runner: SyncRunner) {
        var attempt = 0
        while (true) {
            val outcome = try {
                runner.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // E.g. the local queue could not be read: nothing was deleted, try again later.
                null
            }
            when (outcome) {
                is SyncOutcome.Completed -> {
                    setBanner(SyncBanner.None)
                    return
                }
                is SyncOutcome.SignInRequired -> {
                    setBanner(SyncBanner.SignInRequired(outcome.pending))
                    return
                }
                is SyncOutcome.Locked -> {
                    lock(outcome)
                    return
                }
                is SyncOutcome.RetryLater -> setBanner(SyncBanner.Waiting(outcome.pending))
                null -> Unit
            }
            delay(backoff.delayFor(attempt++).toMillis())
        }
    }

    /** Step 5: tokens and grant were already wiped by the gate; hide Rescuer Mode immediately. */
    private fun lock(outcome: SyncOutcome.Locked) {
        expiryWatch?.cancel()
        _state.value = AuthUiState.Locked(outcome.reason, outcome.pending)
        // Cancels the gate's own collector: a locked device must not upload or refresh again.
        syncJob?.cancel()
    }

    private fun setBanner(banner: SyncBanner) {
        _state.update { if (it is AuthUiState.MissionActive) it.copy(sync = banner) else it }
    }

    private fun stopMissionJobs() {
        expiryWatch?.cancel()
        syncJob?.cancel()
        grantAllowsSync.value = false
    }

    private suspend fun rejectGrant() {
        // A grant for another device / role must not upload anything (OSP-73 Mismatch).
        syncJob?.cancel()
        grantAllowsSync.value = false
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
