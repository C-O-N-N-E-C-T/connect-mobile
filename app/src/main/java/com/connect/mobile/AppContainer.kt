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
import com.connect.mobile.data.session.StoreBackedSessionManager
import com.connect.mobile.data.supabase.NotConfiguredAuthGateway
import com.connect.mobile.data.supabase.SupabaseAuthGateway
import com.connect.mobile.data.supabase.SupabaseKtAuthGateway
import com.connect.mobile.ui.RescuerAuthViewModel
import com.connect.mobile.util.AppLogger
import com.connect.mobile.util.causeChainMessages
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.okhttp.OkHttp

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

    val authGateway: SupabaseAuthGateway by lazy { createGateway() }

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
        bootstrap = SessionBootstrap(sessionStore, logoutUseCase),
    )

    private fun createGateway(): SupabaseAuthGateway {
        val notConfigured = NotConfiguredAuthGateway(sessionStore::deleteSupabaseSession)
        if (!config.isSupabaseConfigured) return notConfigured
        val sessionManager = StoreBackedSessionManager(sessionStore)
        return try {
            val client = createSupabaseClient(config.supabaseUrl, config.supabaseAnonKey) {
                // Same OkHttp engine type as the backend client, so the Network Security Config covers it.
                httpEngine = OkHttp.create()
                // supabase-kt exception/log messages can include request URLs and (masked) headers:
                // keep its logger off; AppLogger covers our own diagnostics without tokens.
                defaultLogLevel = LogLevel.NONE
                install(Auth) {
                    // Tokens live only in SessionStore, not in plaintext SharedPreferences (design 3.2).
                    this.sessionManager = sessionManager
                    codeVerifierCache = MemoryCodeVerifierCache()
                    // TODO(OSP-75): suspend auto-refresh while offline.
                }
            }
            SupabaseKtAuthGateway(client.auth, sessionManager, log)
        } catch (e: IllegalArgumentException) {
            log.debug("invalid Supabase config: ${e.causeChainMessages()}")
            notConfigured
        }
    }

    private companion object {
        /** -> files/datastore/connect_session.preferences_pb */
        const val SESSION_STORE_NAME = "connect_session"
    }
}
