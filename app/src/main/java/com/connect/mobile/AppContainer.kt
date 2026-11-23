package com.connect.mobile

import android.content.Context
import android.os.Build
import androidx.datastore.dataStoreFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.connect.mobile.auth.LoginUseCase
import com.connect.mobile.auth.LogoutUseCase
import com.connect.mobile.auth.SessionBootstrap
import com.connect.mobile.config.AppConfig
import com.connect.mobile.data.backend.AuthBackend
import com.connect.mobile.data.backend.AuthBackendFactory
import com.connect.mobile.data.crypto.AndroidKeystoreMasterKey
import com.connect.mobile.data.db.DbKeyProvider
import com.connect.mobile.data.device.DeviceIdProvider
import com.connect.mobile.data.network.HttpClientFactory
import com.connect.mobile.data.network.NetworkMonitor
import com.connect.mobile.data.session.AeadSupplier
import com.connect.mobile.data.session.DataStoreSessionStore
import com.connect.mobile.data.session.EncryptedSessionStore
import com.connect.mobile.data.session.SessionStore
import com.connect.mobile.data.supabase.AutoRefreshController
import com.connect.mobile.data.supabase.LazyAuthGateway
import com.connect.mobile.data.supabase.NotConfiguredAuthGateway
import com.connect.mobile.data.supabase.SupabaseAuthGateway
import com.connect.mobile.data.supabase.SupabaseKtAuthGateway
import com.connect.mobile.ui.RescuerAuthViewModel
import com.connect.mobile.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

/** Manual dependency injection (about ten objects, one module: no DI framework). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val config: AppConfig = AppConfig.fromBuildConfig()
    val log: AppLogger = AppLogger.android(enabled = BuildConfig.DEBUG)

    // OSP-75: süreç ömrü boyunca yaşayan arka plan işleri (yenileme denetleyicisi vb.).
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // OSP-75: Tink AEAD ile tamamı şifrelenmiş DataStore (files/datastore/connect_session.enc).
    // OSP-74'ün düz metin dosyası varsa ilk okumada taşınır ve silinir. Keystore kullanılamazsa
    // düz metne düşülmez: okuma/yazma IOException ile başarısız olur (fail-closed).
    val sessionStore: SessionStore = EncryptedSessionStore.create(
        produceFile = { appContext.dataStoreFile(EncryptedSessionStore.FILE_NAME) },
        aeadSupplier = sessionAeadSupplier(),
        legacyFile = appContext.dataStoreFile(DataStoreSessionStore.FILE_NAME),
    )

    // OSP-75: SQLCipher parolası; oturum deposundan AYRI dosya (no_backup/connect_db_key.enc).
    // Logout/403/clearAuth bu nesneye erişmez.
    val dbKeyProvider: DbKeyProvider by lazy {
        DbKeyProvider(
            keyFile = File(appContext.noBackupFilesDir, DbKeyProvider.FILE_NAME),
            masterKey = AndroidKeystoreMasterKey(AndroidKeystoreMasterKey.DB_ALIAS),
            // TODO(E8): Room DB dosyasına bağlanacak: `appContext.getDatabasePath(<db adı>).exists()`.
            // Şu an şifreli DB yok; anahtar dosyası yokken yeni anahtar üretmek güvenli.
            encryptedDataExists = { false },
        )
    }

    // OSP-75: çevrimiçi + ön plan + oturum varken token yenilenir, değilse durdurulur.
    private val networkMonitor = NetworkMonitor(appContext, log).also { it.start() }

    // ProcessLifecycleOwner ana iş parçacığında güncellenir; StateFlow'u okumak her iş parçacığından güvenli.
    // STARTED = en az bir Activity görünür (ön plan).
    private val processLifecycle = ProcessLifecycleOwner.get().lifecycle
    private val isForeground = processLifecycle.currentStateFlow
        .map { it.isAtLeast(Lifecycle.State.STARTED) }
        .stateIn(appScope, SharingStarted.Eagerly, processLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

    private val autoRefreshController = AutoRefreshController(networkMonitor.isOnline, isForeground)
        .also { it.start(appScope) }

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

    /**
     * Oturum deposunun ana anahtarı. Anahtar kalıcı olarak kullanılamazsa (ör. geçersiz kılındı) silinir
     * ve yenisi üretilir: depo fail-closed boşalır, yeniden giriş istenir (DB anahtarına dokunmaz).
     */
    private fun sessionAeadSupplier(): AeadSupplier {
        val key = AndroidKeystoreMasterKey(AndroidKeystoreMasterKey.SESSION_ALIAS)
        return object : AeadSupplier {
            override fun get() = key.getOrCreateAead()
            override fun discardKey() = key.deleteKey()
        }
    }

    private fun createGateway(): SupabaseAuthGateway {
        val notConfigured = NotConfiguredAuthGateway(sessionStore::deleteSupabaseSession)
        if (!config.isSupabaseConfigured) return notConfigured
        val gateway = SupabaseKtAuthGateway.createOrNull(
            supabaseUrl = config.supabaseUrl,
            supabaseAnonKey = config.supabaseAnonKey,
            store = sessionStore,
            log = log,
            autoRefreshAllowed = autoRefreshController::isRefreshAllowedNow,
        ) ?: return notConfigured
        // Gateway tembel oluşturulur; denetleyici ancak şimdi yönetebileceği bir oturuma kavuşur.
        autoRefreshController.attach(gateway)
        return gateway
    }
}
