package com.connect.mobile.ui.lock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.connect.mobile.R
import com.connect.mobile.ui.common.SupplementaryDisclaimer

/**
 * "Kilidi Aç" ekranı (OSP-73). Kurtarıcı Modu ve PII ekranlarından önce gösterilir; ekran açılınca
 * BiometricPrompt kendiliğinden bir kez açılır. Parmak izi / yüz / PIN uygulamaya hiç gelmez:
 * yalnızca Android'in "başarılı" sonucu gelir.
 *
 * Çıkış yolu her durumda vardır: biyometri geçici olarak kullanılamıyorsa "Yeniden dene", hiç
 * olmuyorsa [onSignInOnline] (şifreyle çevrimiçi giriş; başarılı giriş kilidi açar, grant silinmez).
 */
@Composable
fun BiometricGate(onUnlocked: () -> Unit, onSignInOnline: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val authenticators = remember { allowedAuthenticators() }
    val biometricManager = remember(context) { BiometricManager.from(context) }
    var availability by remember { mutableIntStateOf(biometricManager.canAuthenticate(authenticators)) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val latestOnUnlocked by rememberUpdatedState(onUnlocked)

    // Kullanıcı Ayarlar'da ekran kilidi kurup döndüğünde durum yeniden okunur.
    LifecycleResumeEffect(biometricManager, authenticators) {
        availability = biometricManager.canAuthenticate(authenticators)
        onPauseOrDispose { }
    }

    val title = stringResource(R.string.lock_prompt_title)
    val subtitle = stringResource(R.string.lock_prompt_subtitle)
    val promptInfo = remember(title, subtitle, authenticators) {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            // DEVICE_CREDENTIAL izinliyken negatif buton verilmez (kütüphane kuralı).
            .setAllowedAuthenticators(authenticators)
            .build()
    }
    val prompt = remember(activity) {
        activity?.let {
            BiometricPrompt(
                it,
                ContextCompat.getMainExecutor(it),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        errorText = null
                        latestOnUnlocked()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        errorText = if (errorCode in SILENT_ERRORS) null else errString.toString()
                    }
                },
            )
        }
    }
    val canPrompt = prompt != null && availability == BiometricManager.BIOMETRIC_SUCCESS
    val needsScreenLock = availability == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
        availability == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE

    LaunchedEffect(canPrompt) {
        if (canPrompt) prompt?.authenticate(promptInfo)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.lock_text), style = MaterialTheme.typography.bodyLarge)
        when {
            canPrompt -> Button(
                onClick = { prompt?.authenticate(promptInfo) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.lock_unlock_button)) }

            needsScreenLock -> {
                // Karar 4: ekran kilidi yoksa Kurtarıcı Modu açılmaz; Kurban/Röle etkilenmez.
                Text(stringResource(R.string.lock_no_credential), color = MaterialTheme.colorScheme.error)
                Button(onClick = { context.openSecuritySettings() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.lock_open_settings))
                }
            }

            else -> {
                // Ör. sensör meşgul / güvenlik güncellemesi gerekli / durum bilinmiyor: yeniden okunur.
                Text(stringResource(R.string.lock_unavailable), color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = { availability = biometricManager.canAuthenticate(authenticators) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.lock_retry)) }
            }
        }
        errorText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        // Biyometri / ekran kilidi hiç çalışmazsa kurtarıcı dışarıda kalmasın: şifreyle çevrimiçi giriş.
        OutlinedButton(onClick = onSignInOnline, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.lock_sign_in_online))
        }
        SupplementaryDisclaimer(modifier = Modifier.fillMaxWidth())
    }
}

private val SILENT_ERRORS = setOf(
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
)

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

private fun Context.openSecuritySettings() {
    try {
        startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    } catch (e: ActivityNotFoundException) {
        // Ayarlar ekranı yoksa bir şey yapılamaz; mesaj ekranda kalır.
    }
}
