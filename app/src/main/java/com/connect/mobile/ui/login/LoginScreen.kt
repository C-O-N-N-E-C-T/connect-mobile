package com.connect.mobile.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.connect.mobile.R
import com.connect.mobile.ui.AuthUiState
import com.connect.mobile.ui.LoginForm
import com.connect.mobile.ui.common.SupplementaryDisclaimer
import com.connect.mobile.ui.common.messageRes

@Composable
fun LoginScreen(
    state: AuthUiState,
    form: LoginForm,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idle = state as? AuthUiState.LoginIdle
    val busy = idle == null
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.login_online_note), style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = form.email,
            onValueChange = onEmailChange,
            label = { Text(stringResource(R.string.login_email)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!busy) onSubmit() }),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(stringResource(if (showPassword) R.string.login_hide_password else R.string.login_show_password))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        idle?.error?.let {
            Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error)
        }
        if (idle?.loggedOut == true && idle.error == null) {
            Text(stringResource(R.string.login_logged_out), color = MaterialTheme.colorScheme.primary)
        }

        Button(onClick = onSubmit, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.login_submit))
        }
        progressText(state)?.let { text ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(text))
            }
        }

        SupplementaryDisclaimer(modifier = Modifier.fillMaxWidth())
    }
}

private fun progressText(state: AuthUiState): Int? = when (state) {
    AuthUiState.SigningIn -> R.string.login_progress_signing_in
    AuthUiState.BindingSession -> R.string.login_progress_binding
    AuthUiState.TearingDown -> R.string.login_progress_tearing_down
    else -> null
}
