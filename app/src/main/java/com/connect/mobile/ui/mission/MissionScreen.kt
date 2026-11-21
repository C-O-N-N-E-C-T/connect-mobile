package com.connect.mobile.ui.mission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.connect.mobile.R
import com.connect.mobile.data.session.StoredGrant
import com.connect.mobile.ui.common.ExpiryFormatter
import com.connect.mobile.ui.common.SupplementaryDisclaimer

// TODO(OSP-73): OfflineGrantPolicy gate (monotonic clock) + BiometricPrompt before showing Field Rescuer Mode.
@Composable
fun MissionScreen(
    grant: StoredGrant,
    expired: Boolean,
    onEndMission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    // Not remembered: a time-zone change does not recreate the activity, so format on every composition.
    val expiry = ExpiryFormatter.format(grant.expiresAt)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (expired) {
            // Wall-clock expiry (review R2): no "active" claim; only End Mission / Log Out remains.
            Text(
                stringResource(R.string.mission_expired),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(stringResource(R.string.mission_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.mission_authenticated_until, expiry), style = MaterialTheme.typography.bodyLarge)
        }
        Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.mission_end_button))
        }
        SupplementaryDisclaimer(modifier = Modifier.fillMaxWidth())
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.mission_end_dialog_title)) },
            text = { Text(stringResource(R.string.mission_end_dialog_text)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onEndMission() }) {
                    Text(stringResource(R.string.mission_end_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.mission_end_dialog_cancel)) }
            },
        )
    }
}
