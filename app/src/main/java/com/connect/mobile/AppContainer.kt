package com.connect.mobile

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.preferencesDataStoreFile
import com.connect.mobile.auth.LoginUseCase
import com.connect.mobile.auth.LogoutUseCase
import com.connect.mobile.auth.SessionBootstrap
import com.connect.mobile.config.AppConfig
import com.connect.mobile.data.backend.AuthBackend
import com.connect.mobile.data.backend.AuthBackendFactory
import com.connect.mobile.data.device.DeviceIdProvider
import com.connect.mobile.data.network.HttpClientFactory
import com.connect.mobile.data.session.DataStoreSessionStore
import com.connect.mobile.data.session.SessionStore
import com.connect.mobile.data.supabase.LazyAuthGateway
import com.connect.mobile.data.supabase.NotConfiguredAuthGateway
import com.connect.mobile.data.supabase.SupabaseAuthGateway
import com.connect.mobile.data.supabase.SupabaseKtAuthGateway
import com.connect.mobile.ui.RescuerAuthViewModel
import com.connect.mobile.util.AppLogger

/** Manual dependency injection (about ten objects, one module: no DI framework). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val config: AppConfig = AppConfig.fromBuildConfig()
    val log: AppLogger = AppLogger.android(enabled = BuildConfig.DEBUG)

    // TODO(OSP-75): replace with the encrypted SessionStore.
    val sessionStore: SessionStore = DataStoreSessionStore.create(
        produceFile = { appContext.preferencesDataStoreFile(SESSION_STORE_NAME) },
    )

    val deviceIdProvider = DeviceIdProvider(sessionStore) { "${Build.MANUFACTURER} ${Build.MODEL}" }

    private val httpClient by lazy { HttpClientFactory.create() }

    val authBackend: AuthBackend by lazy { AuthBackendFactory.create(config, httpClient, log) }

    // Lazy delegate: the SupabaseClient is built on first use, not when the ViewModel is created (R8).
    val authGateway: SupabaseAuthGateway = LazyAuthGateway { createGateway() }

    private val logoutUseCase by lazy { LogoutUseCase(authGateway, authBackend, sessionStore) }

    fun newRescuerAuthViewModel() = RescuerAuthViewModel(
        login = LoginUseCase(
            isConfigured = config.isConfigured,
            gateway = authGateway,
            backend = authBackend,
            store = sessionStore,
            deviceIdProvider = deviceIdProvider,
            logout = logoutUseCase,
        ),
        logout = logoutUseCase,
        bootstrap = SessionBootstrap(sessionStore, deviceIdProvider, logoutUseCase),
    )

    private fun createGateway(): SupabaseAuthGateway {
        val notConfigured = NotConfiguredAuthGateway(sessionStore::deleteSupabaseSession)
        if (!config.isSupabaseConfigured) return notConfigured
        return SupabaseKtAuthGateway.createOrNull(config.supabaseUrl, config.supabaseAnonKey, sessionStore, log)
            ?: notConfigured
    }

    private companion object {
        /** -> files/datastore/connect_session.preferences_pb */
        const val SESSION_STORE_NAME = "connect_session"
    }
}
