package com.connect.mobile.ui.mission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.connect.mobile.R
import com.connect.mobile.auth.AuthError
import com.connect.mobile.ui.common.SupplementaryDisclaimer
import com.connect.mobile.ui.common.messageRes

/**
 * OSP-76 step 5: shown after 403 DEVICE_REVOKED / ACCOUNT_DISABLED during upload. No mission data and
 * no victim PII is shown. The unsynced records are kept encrypted on the device; signing in again
 * (e.g. after the account is reactivated) lets them upload.
 */
@Composable
fun LockedScreen(
    reason: AuthError,
    pendingUploads: Int,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.sync_locked_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(reason.messageRes()), color = MaterialTheme.colorScheme.error)
        if (pendingUploads > 0) {
            Text(stringResource(R.string.sync_locked_records_kept, pendingUploads))
        }
        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sync_locked_sign_in))
        }
        SupplementaryDisclaimer(modifier = Modifier.fillMaxWidth())
    }
}
