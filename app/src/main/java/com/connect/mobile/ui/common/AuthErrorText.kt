package com.connect.mobile.ui.common

import androidx.annotation.StringRes
import com.connect.mobile.R
import com.connect.mobile.auth.AuthError

/** Localised UI text for every AuthError (server messages are never shown). */
@StringRes
fun AuthError.messageRes(): Int = when (this) {
    AuthError.InvalidInput -> R.string.error_invalid_input
    AuthError.BadCredentials -> R.string.error_bad_credentials
    AuthError.AccountDisabled -> R.string.error_account_disabled
    AuthError.AccountNotReady -> R.string.error_account_not_ready
    AuthError.RateLimited -> R.string.error_rate_limited
    AuthError.Network -> R.string.error_network
    AuthError.RoleNotAllowed -> R.string.error_role_not_allowed
    AuthError.SessionRejected -> R.string.error_session_rejected
    AuthError.DeviceRevoked -> R.string.error_device_revoked
    AuthError.RequestRejected -> R.string.error_request_rejected
    AuthError.GrantInvalid -> R.string.error_grant_invalid
    AuthError.Server -> R.string.error_server
    AuthError.NotConfigured -> R.string.error_not_configured
}
