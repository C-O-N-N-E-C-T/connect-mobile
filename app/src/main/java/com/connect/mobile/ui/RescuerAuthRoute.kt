package com.connect.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.connect.mobile.R
import com.connect.mobile.ui.common.SupplementaryDisclaimer
import com.connect.mobile.ui.lock.BiometricGate
import com.connect.mobile.ui.login.LoginScreen
import com.connect.mobile.ui.mission.LockedScreen
import com.connect.mobile.ui.mission.MissionScreen

@Composable
fun RescuerAuthRoute(viewModel: RescuerAuthViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val locked by viewModel.locked.collectAsStateWithLifecycle()

    Scaffold { padding ->
        // imePadding: the keyboard must not cover the password field or the Sign in button.
        val modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding()
        when (val s = state) {
            AuthUiState.Bootstrapping -> Busy(R.string.loading, modifier)
            AuthUiState.LoggingOut -> Busy(R.string.mission_logging_out, modifier)
            // OSP-73: Field Rescuer Mode only after biometric / device credential (restart, >15 min background).
            is AuthUiState.MissionActive -> if (locked) {
                BiometricGate(onUnlocked = viewModel::onUnlocked, modifier = modifier)
            } else {
                MissionScreen(
                    grant = s.grant,
                    expired = s.expired,
                    onEndMission = viewModel::endMission,
                    modifier = modifier,
                    sync = s.sync,
                    onSignInToUpload = viewModel::signInToUpload,
                )
            }
            // OSP-76 step 5: Rescuer Mode and PII locked after 403 DEVICE_REVOKED / ACCOUNT_DISABLED.
            is AuthUiState.Locked -> LockedScreen(
                reason = s.reason,
                pendingUploads = s.pendingUploads,
                onSignIn = viewModel::leaveLock,
                modifier = modifier,
            )
            else -> LoginScreen(
                state = s,
                form = form,
                onEmailChange = viewModel::onEmailChange,
                onPasswordChange = viewModel::onPasswordChange,
                onSubmit = viewModel::submit,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun Busy(text: Int, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(stringResource(text))
        SupplementaryDisclaimer(modifier = Modifier.fillMaxWidth())
    }
}
