package com.connect.mobile.data.supabase

import com.connect.mobile.auth.AuthError
import com.connect.mobile.data.session.SessionStore
import com.connect.mobile.data.session.StoreBackedSessionManager
import com.connect.mobile.util.AppLogger
import com.connect.mobile.util.causeChainMessages
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.Base64

class SupabaseKtAuthGateway(
    internal val auth: Auth, // internal for tests only
    private val sessionManager: StoreBackedSessionManager,
    private val log: AppLogger = AppLogger.NONE,
    private val initTimeoutMs: Long = 5_000,
    // OSP-75: ilk yüklemede yenilemenin hemen açılıp açılmayacağı (çevrimiçi + ön plan).
    private val autoRefreshAllowed: () -> Boolean = { true },
    loadScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SupabaseAuthGateway, AutoRefreshTarget {

    /**
     * OSP-75: supabase-kt'nin kendi otomatik yüklemesi kapalıdır (`autoLoadFromStorage = false`); saklı
     * oturumu burada, yenilemeyi o anki ağ/ön plan durumuna göre açarak yükleriz. Böylece çevrimdışı
     * oluşturulan istemci süresi geçmiş token için ~10 sn'lik başarısız yenileme döngüsüne girmez.
     * Ayrıca `enableLifecycleCallbacks = false` iken supabase-kt durumu yükleme bitmeden
     * NotAuthenticated yapabildiği için [awaitInitialized] supabase-kt'yi değil bu yüklemeyi bekler.
     */
    /** D4: ilk (veya sonraki) yükleme depo hatasıyla başarısız oldu; bir sonraki resume tekrar dener. */
    private val loadRetryNeeded = MutableStateFlow(false)

    private val initialLoad: Deferred<Unit> = loadScope.async {
        try {
            auth.loadFromStorage(autoRefresh = autoRefreshAllowed())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Okunamayan depo: oturum yok sayılır (supabase-kt'nin kendi yüklemesiyle aynı davranış).
            log.debug("supabase session load failed: ${e.javaClass.simpleName}")
        }
        loadRetryNeeded.value = sessionManager.lastLoadFailed
        // O3: yükleme sürerken ağ düşmüş / arka plana geçilmiş olabilir. Karar yeniden okunur; artık
        // izin yoksa yüklemenin başlattığı yenileme durdurulur (aksi halde çevrimdışı döngü başlardı).
        if (!autoRefreshAllowed()) auth.stopAutoRefreshForCurrentSession()
    }

    override suspend fun awaitInitialized() {
        withTimeoutOrNull(initTimeoutMs) {
            initialLoad.await()
            auth.awaitInitialization()
        }
    }

    /**
     * Yenilenebilir oturum var mı. Başarısız bir yükleme de "var" sayılır (D4): denetleyici çevrimiçi
     * + ön planda resume çağırsın ve [resumeAutoRefresh] yüklemeyi yeniden denesin.
     */
    override val hasSession: Flow<Boolean> = combine(auth.sessionStatus, loadRetryNeeded) { status, retry ->
        status is SessionStatus.Authenticated || status is SessionStatus.RefreshFailure || retry
    }.distinctUntilChanged()

    override suspend fun resumeAutoRefresh() {
        awaitInitialized()
        // Logout sonrası (kayıt kapısı kapalı) depoda oturum yoktur; yeniden yükleme denenmez.
        if (sessionManager.savesBlocked) return
        val status = auth.sessionStatus.value
        if (loadRetryNeeded.value && status !is SessionStatus.Authenticated && status !is SessionStatus.RefreshFailure) {
            reloadWithAutoRefresh()
            return
        }
        when (status) {
            is SessionStatus.Authenticated -> {
                if (auth.isAutoRefreshRunning) return
                try {
                    auth.startAutoRefreshForCurrentSession()
                } catch (e: IllegalStateException) {
                    // Tuzak: start, durum kontrolümüzden sonra değişirse "No session found" (ISE) veya
                    // Authenticated'a zorla dönüşüm (ClassCastException) atar. Depodan yeniden yükle.
                    reloadWithAutoRefresh()
                } catch (e: ClassCastException) {
                    reloadWithAutoRefresh()
                }
            }
            // Çevrimdışı yenileme başarısız oldu: supabase-kt oturumu "current" saymaz, start ISE atardı.
            // Depodaki oturum yeniden içe aktarılır; süresi geçmişse hemen yenilenir.
            is SessionStatus.RefreshFailure -> reloadWithAutoRefresh()
            // Oturum yok (veya hâlâ yükleniyor): yenileme başlatılmaz.
            else -> Unit
        }
    }

    override fun pauseAutoRefresh() {
        auth.stopAutoRefreshForCurrentSession()
    }

    /**
     * O1: logout ile yarış. Kontrol ile yükleme arasında veya yükleme sürerken [clearLocalSession]
     * çalışabilir; o durumda yükleme oturumu belleğe geri getirebilirdi. Kapı yüklemeden önce ve
     * sonra yeniden kontrol edilir; kapalıysa yüklenen oturum hemen silinir ve yenileme durdurulur.
     */
    private suspend fun reloadWithAutoRefresh() {
        if (sessionManager.savesBlocked) return
        auth.loadFromStorage(autoRefresh = true)
        loadRetryNeeded.value = sessionManager.lastLoadFailed
        if (sessionManager.savesBlocked) {
            loadRetryNeeded.value = false
            auth.stopAutoRefreshForCurrentSession()
            auth.clearSession()
            auth.stopAutoRefreshForCurrentSession()
        }
    }

    override suspend fun signIn(email: String, password: String): SignInOutcome {
        awaitInitialized()
        // supabase-kt saves the new session inside signInWith, so re-open the save gate first.
        sessionManager.allowSaves()
        return try {
            auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            SignInOutcome.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthRestException) {
            log.debug("supabase sign-in rejected: ${e.errorCode?.value} (${e.statusCode})")
            SignInOutcome.Failure(SupabaseErrorMapper.fromRest(e.errorCode?.value, e.statusCode))
        } catch (e: RestException) {
            log.debug("supabase sign-in rejected: ${e.error} (${e.statusCode})")
            SignInOutcome.Failure(SupabaseErrorMapper.fromRest(null, e.statusCode))
        } catch (e: IOException) {
            // supabase-kt HttpRequestException / timeouts / the OS cleartext refusal. HttpRequestException
            // has no cause: the OS message is inside its own message ("HTTP request to <url> (<method>)
            // failed with message: …"). URL and method are not secret; AppLogger is debug-only.
            log.debug("supabase network failure: ${e.causeChainMessages()}")
            SignInOutcome.Failure(AuthError.Network)
        } catch (e: Exception) {
            log.debug("supabase sign-in failure: ${e.javaClass.simpleName}")
            SignInOutcome.Failure(AuthError.Server)
        }
    }

    override suspend fun accessTokenOrNull(): String? {
        awaitInitialized()
        return auth.currentAccessTokenOrNull()
    }

    override suspend fun currentUserIdOrNull(): String? {
        awaitInitialized()
        val session = auth.currentSessionOrNull() ?: return null
        // The password-grant response carries the user; the JWT `sub` is the same id (fallback).
        return session.user?.id ?: jwtSubject(session.accessToken)
    }

    override suspend fun signOut() {
        auth.signOut(SignOutScope.LOCAL)
    }

    override suspend fun clearLocalSession() {
        // Review R7: close the save gate and stop auto-refresh on both sides of clearSession(), so a
        // refresh job recreated concurrently cannot escape and re-persist the tokens.
        sessionManager.blockSaves()
        // OSP-75: logout sonrası yükleme tekrarı yok (hasSession false olur).
        loadRetryNeeded.value = false
        auth.stopAutoRefreshForCurrentSession()
        auth.clearSession()
        auth.stopAutoRefreshForCurrentSession()
    }

    companion object {
        /**
         * Builds the SupabaseClient with the app's Auth configuration (design 3.2). Returns null for an
         * unusable config: supabase-kt throws IllegalArgumentException / IllegalStateException (e.g. a
         * SUPABASE_URL containing "auth/v1"), and the app must then show "not configured", not crash.
         */
        fun createOrNull(
            supabaseUrl: String,
            supabaseAnonKey: String,
            store: SessionStore,
            log: AppLogger = AppLogger.NONE,
            engine: HttpClientEngine = OkHttp.create(),
            initTimeoutMs: Long = 5_000,
            autoRefreshAllowed: () -> Boolean = { true },
        ): SupabaseKtAuthGateway? = try {
            val sessionManager = StoreBackedSessionManager(store)
            val client = createSupabaseClient(supabaseUrl, supabaseAnonKey) {
                // Same OkHttp engine type as the backend client, so the Network Security Config covers it.
                httpEngine = engine
                // supabase-kt exception/log messages can include request URLs and (masked) headers:
                // keep its logger off; AppLogger covers our own diagnostics without tokens.
                defaultLogLevel = LogLevel.NONE
                install(Auth) {
                    // Tokens live only in SessionStore, not in plaintext SharedPreferences (design 3.2).
                    this.sessionManager = sessionManager
                    codeVerifierCache = MemoryCodeVerifierCache()
                    // OSP-75: yenileme AutoRefreshController'a ait (çevrimiçi + ön plan + oturum).
                    // supabase-kt'nin yaşam döngüsü geri çağrıları ağı bilmez; kapalı.
                    this.enableLifecycleCallbacks = false
                    // Oturum varken yenileme varsayılan olarak açık kalır (giriş anında çevrimiçiyiz);
                    // durdurma/sürdürme kararını denetleyici verir.
                    alwaysAutoRefresh = true
                    // Saklı oturumu gateway kendisi yükler (bkz. SupabaseKtAuthGateway.initialLoad).
                    autoLoadFromStorage = false
                }
            }
            SupabaseKtAuthGateway(client.auth, sessionManager, log, initTimeoutMs, autoRefreshAllowed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("invalid Supabase config: ${e.causeChainMessages()}")
            null
        }
    }
}

/** `sub` claim of a JWT, decoded WITHOUT verification (only compared with the backend's answer). */
internal fun jwtSubject(jwt: String): String? = runCatching {
    val payload = jwt.split('.')[1]
    val text = String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8)
    (Json.parseToJsonElement(text).jsonObject["sub"] as? JsonPrimitive)?.contentOrNull
}.getOrNull()

/** Used when SUPABASE_URL / SUPABASE_ANON_KEY are missing: no client is created at all. */
class NotConfiguredAuthGateway(private val deleteStoredSession: suspend () -> Unit) : SupabaseAuthGateway {
    override suspend fun awaitInitialized() = Unit
    override suspend fun signIn(email: String, password: String) = SignInOutcome.Failure(AuthError.NotConfigured)
    override suspend fun accessTokenOrNull(): String? = null
    override suspend fun currentUserIdOrNull(): String? = null
    override suspend fun signOut() = Unit
    override suspend fun clearLocalSession() = deleteStoredSession()
}
