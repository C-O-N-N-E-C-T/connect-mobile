package com.connect.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.connect.mobile.ui.login.LoginScreen
import com.connect.mobile.ui.mission.MissionScreen

@Composable
fun RescuerAuthRoute(viewModel: RescuerAuthViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()

    Scaffold { padding ->
        val modifier = Modifier.padding(padding)
        when (val s = state) {
            AuthUiState.Bootstrapping -> Busy(R.string.loading, modifier)
            AuthUiState.LoggingOut -> Busy(R.string.mission_logging_out, modifier)
            is AuthUiState.MissionActive -> MissionScreen(s.grant, onEndMission = viewModel::endMission, modifier = modifier)
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
