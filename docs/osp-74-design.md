# OSP-74 — Android scaffold + Field Rescuer pre-deployment login (design, checkpoint 0)

Scope: FR-R-15 / NFR-SEC-02 / NFR-SAFE-03 (AC-29). Single `app` module, package and applicationId
`com.connect.mobile`, minSdk 26. Manual constructor injection through one `AppContainer` (no DI
framework: about 10 objects, one module). No features beyond the story.
Seams are left for OSP-75 (encrypted storage), OSP-73 (grant policy and biometric lock) and OSP-76
(reconnection).

## 0. Changes approved by the user (2026-11-19)
1. Stay on compileSdk/targetSdk 37 (installed `android-37.0`), minSdk 26, Compose BOM 2026.09.00; no move to 37.1.
2. Disclaimer text is the SoW NFR-SAFE-03 wording (section 5).
3. OSP-60 (backend `/auth/session`, `/auth/logout`) is not started. Every code location that depends on
   a contract assumption carries the greppable comment `// OSP-60 ASSUMPTION: <what> — see docs/osp-60-contract-assumptions.md`;
   that file (EN + TR) is the handoff note for the backend developer (replaces section 8 items 1–4).
4. Team rule: nobody installs Gradle or Kotlin system-wide. The Gradle 9.7.1 wrapper is committed with
   `distributionSha256Sum` (official checksum); Kotlin only via the Gradle plugins / version catalog;
   `kotlin { jvmToolchain(21) }`, no toolchain auto-provisioning plugin.
5. README.md documents requirements, `./gradlew` only, the `local.properties` keys (never committed —
   repos are public), build/test commands, the HTTPS-only rule and the debug fake backend.
6. Debug testing = real hosted Supabase (URL/anon key in `local.properties`) + debug-only fake
   `AuthBackend` for OSP-60. No fake Supabase sign-in. The fake is unreachable in release.
7. Device tests run later on the user's own phone; here the instrumented cleartext test must only
   compile (`assembleDebugAndroidTest`).
8. Verification after **every** checkpoint is the full command
   `timeout 1500 ./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug assembleDebugAndroidTest`.

Implementation notes (deviations found while coding, 2026-11-19):
- `AppConfig.isConfigured` needs `API_BASE_URL` only when the debug fake backend is off; with the fake,
  `SUPABASE_URL` + `SUPABASE_ANON_KEY` are enough (section 3.7 said all three).
- A debug-only `AppLogger` (`adb logcat -s CONNECT`) logs the cause chain of network failures, so the
  OS message "CLEARTEXT communication … not permitted" is visible (section 7, manual check 4). Tokens,
  passwords, headers and bodies are never logged.
- `AuthError` was added in 1.3 (the gateway maps Supabase errors to it), not 1.4.
- AGP 9 creates unit-test tasks only for the tested build type; `gradle.properties` sets
  `android.onlyEnableUnitTestForTheTestedBuildType=false` so `testReleaseUnitTest` exists (it proves
  `src/test` does not depend on the debug-only fake).
- supabase-kt's own logger is off (`LogLevel.NONE`): its exception messages include request URLs and
  masked headers.
- Extra files not in section 2: `util/AppLogger.kt`, `ui/RescuerAuthRoute.kt` (state -> screen),
  `src/test/.../security/NetworkSecurityConfigTest.kt`.

## 1. Toolchain and library versions (verified 2026-11-19)

Every version below was checked against the repository metadata. The full dependency set was also
built in a throwaway spike project (in the scratchpad, not in this repo) with
`assembleDebug assembleRelease testDebugUnitTest lintDebug assembleDebugAndroidTest` (including the
androidx.test artifacts and an instrumented test against `NetworkSecurityPolicy`). The spike passed.

| Item | Version | Source / note |
|---|---|---|
| Gradle (wrapper) | **9.7.1** | services.gradle.org (latest 9.9.1); 9.7.1 is cached locally and passed the spike |
| AGP | **9.5.1** | dl.google.com `com/android/tools/build/gradle` (latest stable). AGP 9 has built-in Kotlin, so no `kotlin-android` plugin |
| Kotlin (KGP, compose and serialization plugins) | **2.4.21** | Maven Central. AGP pulls KGP 2.2.10; `buildEnvironment` shows it resolved to 2.4.21. supabase-kt 3.9.0 is compiled with 2.4.21 |
| compileSdk / targetSdk | **37 / 37** | `compileSdk = 37` resolves to the installed `platforms/android-37.0` (API 37.0, Platform 17). Nothing installed |
| JDK / jvmToolchain | 21 (Java target 17) | installed OpenJDK 21.0.13 |
| Compose BOM | **2026.09.00** (UI 1.12.1, Material3 1.4.0) | dl.google.com. The latest BOM, 2026.11.01 (Compose 1.13.0), **fails**: it needs compileSdk 37.1, which is not installed (see section 8) |
| activity-compose | 1.13.0 | dl.google.com; works with compileSdk 37.0 |
| lifecycle-viewmodel-compose / runtime-compose | 2.11.1 | dl.google.com |
| core-ktx | 1.19.1 | dl.google.com; spike `assembleDebug assembleRelease` OK with compileSdk 37.0 |
| material-icons-core | 1.7.8 (via BOM) | used only for the disclaimer info icon |
| DataStore Preferences | 1.3.0 | dl.google.com |
| supabase-kt BOM / `auth-kt` | **3.9.0** | Maven Central `io.github.jan-tennert.supabase:bom` |
| Ktor (`client-okhttp`, `content-negotiation`, `serialization-kotlinx-json`, `client-mock` for tests) | **3.6.0** | Same version that supabase-kt 3.9.0 depends on. OkHttp 5.5.0 is transitive and is the **only** engine on the classpath (no CIO) |
| kotlinx-serialization-json | 1.11.0 | Matches supabase-kt (1.13.0 exists; staying aligned) |
| kotlinx-coroutines(-test) | 1.11.0 | Maven Central; same as supabase-kt |
| JUnit 4 / androidx.test.ext:junit / androidx.test:runner | 4.13.2 / 1.3.0 / 1.7.0 | Maven Central / dl.google.com |

No Robolectric (DataStore is tested on plain JVM with a temp file). Versions live in
`gradle/libs.versions.toml`.

## 2. File layout

```
.gitignore                      # local.properties, .gradle/, build/, .idea/, *.iml, .kotlin/, captures/
settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/libs.versions.toml
gradlew  gradlew.bat  gradle/wrapper/gradle-wrapper.{jar,properties}   # 9.7.1 + distributionSha256Sum
README.md                       # requirements, ./gradlew only, local.properties keys, HTTPS-only, fake backend
docs/osp-60-contract-assumptions.md   # EN + TR handoff note for the OSP-60 backend developer
app/build.gradle.kts            # reads local.properties -> BuildConfig (see section 3.7)
app/src/main/AndroidManifest.xml   # INTERNET; networkSecurityConfig; allowBackup=false; dataExtractionRules
app/src/main/res/xml/network_security_config.xml   # <base-config cleartextTrafficPermitted="false"/>, no domain exceptions
app/src/main/res/xml/data_extraction_rules.xml     # exclude everything from cloud backup and device transfer
app/src/main/res/values/strings.xml     app/src/main/res/values-tr/strings.xml
app/src/main/java/com/connect/mobile/
  ConnectApplication.kt          # creates AppContainer
  AppContainer.kt                # manual DI; builds SupabaseClient lazily only if configured
  MainActivity.kt                # setContent { ConnectTheme { RescuerAuthRoute(...) } }
  config/AppConfig.kt            # BuildConfig -> AppConfig(isConfigured = all three values non-blank)
  data/session/SessionStore.kt               # interface + StoredGrant
  data/session/DataStoreSessionStore.kt      # TEMP plaintext impl  // TODO(OSP-75)
  data/session/StoreBackedSessionManager.kt  # supabase-kt SessionManager -> SessionStore
  data/device/DeviceIdProvider.kt            # install UUID + device_name
  data/network/HttpClientFactory.kt          # single OkHttp-engine Ktor client config
  data/backend/AuthBackend.kt                # interface + BackendResult/BackendError
  data/backend/AuthDtos.kt                   # ALL wire DTOs (grant key assumption isolated here)
  data/backend/KtorAuthBackend.kt            # real impl -> API_BASE_URL
  data/supabase/SupabaseAuthGateway.kt       # interface (signIn / signOut / clearLocalSession / accessToken)
  data/supabase/SupabaseKtAuthGateway.kt     # supabase-kt impl
  auth/AuthError.kt                          # sealed error model (section 3.6)
  auth/LoginUseCase.kt                       # sign in -> bind -> role gate -> validate grant -> persist
  auth/LogoutUseCase.kt                      # teardown: best-effort remote + guaranteed scoped wipe
  auth/SessionBootstrap.kt                   # start-up decision (grant present / stale session wipe)
  ui/RescuerAuthViewModel.kt                 # state machine (section 4)
  ui/login/LoginScreen.kt   ui/mission/MissionScreen.kt
  ui/common/SupplementaryDisclaimer.kt       # shared composable (NFR-SAFE-03 / AC-29)
  ui/common/AuthErrorText.kt                 # AuthError -> @StringRes
  ui/common/ExpiryFormatter.kt               # Instant -> local-zone, locale-aware text
  ui/theme/Theme.kt
app/src/debug/java/com/connect/mobile/data/backend/FakeAuthBackend.kt
app/src/debug/java/com/connect/mobile/data/backend/AuthBackendFactory.kt    # flag -> Fake or Ktor
app/src/release/java/com/connect/mobile/data/backend/AuthBackendFactory.kt  # always Ktor
app/src/test/java/com/connect/mobile/...         # section 7
app/src/testDebug/java/com/connect/mobile/data/backend/FakeAuthBackendTest.kt
app/src/androidTest/java/com/connect/mobile/CleartextPolicyTest.kt
```

## 3. Interfaces and contracts

### 3.1 SessionStore (temporary implementation; OSP-75 replaces it)
```kotlin
interface SessionStore {
    suspend fun getOrCreateDeviceId(generate: () -> String): String   // atomic (single DataStore edit)
    suspend fun readSupabaseSession(): String?        // raw UserSession JSON owned by supabase-kt
    suspend fun writeSupabaseSession(json: String)
    suspend fun deleteSupabaseSession()                // ONLY the session key
    suspend fun readGrant(): StoredGrant?
    suspend fun writeGrant(grant: StoredGrant)
    suspend fun clearGrant()                           // removes {grant_*} ONLY (logout step 1)
    suspend fun clearAuth()                            // removes {supabase_session, grant_*} ONLY
    val grant: Flow<StoredGrant?>
}
data class StoredGrant(val userId: String, val deviceId: String, val role: String,
                       val authenticatedAt: Instant, val expiresAt: Instant)   // java.time.Instant
```
- `DataStoreSessionStore` (`// TODO(OSP-75): replace with Tink AEAD encrypted store`) uses its own
  file, `connect_session.preferences_pb`, through `PreferenceDataStoreFactory.create(produceFile=…)`
  so tests can inject a temp file.
- `clearAuth()` does `edit { it.remove(k) }` for an explicit key list. It never calls `clear()` and
  never deletes the file. `device_id` is never in that list.
- The store has no reference to any victim, relay or DB file.
- **Store errors (review R4).** `create()` passes `ReplaceFileCorruptionHandler { emptyPreferences() }`,
  so a corrupt file fails closed (no grant, no tokens, new `device_id`) instead of crashing every
  launch. Other I/O errors are caught by the callers:
  - bootstrap error → `LoginIdle(error = Server)`;
  - any exception after sign-in (e.g. `writeGrant` on a full disk) → full teardown + `Server`;
  - a failed `clearAuth()` on logout → `LoginIdle(error = Server)` instead of "logged out".

### 3.2 supabase-kt session persistence: finding and integration
- **Finding (auth-kt 3.9.0 sources):** if `AuthConfig.sessionManager` is null, the client uses
  `SettingsSessionManager`. It is backed by multiplatform-settings `Settings()`. On Android that means
  `getSharedPreferences("${packageName}_preferences")`, a **plaintext** `UserSession` JSON (access and
  refresh tokens) stored under the key `sb-<url>-session`. That is a second, unencrypted copy of the
  tokens. `codeVerifierCache` also defaults to SharedPreferences.
- **Decision:**
  - Set `install(Auth) { sessionManager = StoreBackedSessionManager(store); codeVerifierCache = MemoryCodeVerifierCache() }`.
    The tokens then live only in the SessionStore. PKCE is unused (password grant), so a memory
    cache is enough.
  - The adapter serialises `UserSession` with `Json { encodeDefaults = true }`. This is required by
    supabase-kt.
  - The adapter's `deleteSession()` calls `store.deleteSupabaseSession()`. It never touches the
    grant or `device_id`.
- **Consequence:** supabase-kt calls `saveSession` as soon as `signInWith(Email)` succeeds, before the
  role check. The tokens of a coordinator or administrator are therefore written briefly. They are
  removed by the teardown (section 4.2).
- **Start-up rule (`SessionBootstrap`):** a Supabase session with **no** grant is an interrupted or
  rejected login. It is wiped through the same teardown, without remote calls.
- **Start-up race:** with `autoLoadFromStorage` (default), `AuthImpl.init()` loads the stored session
  in `authScope.launch { … }`, i.e. asynchronously. A wipe that runs before that load finishes would be
  undone (the session becomes active in memory and is re-saved on the next refresh). Therefore
  `SupabaseKtAuthGateway` calls `withTimeoutOrNull(5 s) { auth.awaitInitialization() }` before any
  token read, sign-in or `clearSession()`. The timeout keeps offline logout from hanging.
- **Auto-refresh:** keep the defaults (`alwaysAutoRefresh`, `enableLifecycleCallbacks`) and add
  `// TODO(OSP-75): suspend auto-refresh while offline`.
- **Re-persist hardening (review R7).** supabase-kt's `sessionJob` is a plain `var`, so a refresh job
  recreated concurrently with `clearSession()` could escape cancellation and save the tokens again
  later.
  - `clearLocalSession()` therefore calls `stopAutoRefreshForCurrentSession()` before **and** after
    `auth.clearSession()`.
  - `StoreBackedSessionManager` has a save gate. `clearLocalSession()` closes it and `signIn()`
    re-opens it before `signInWith`, because supabase-kt saves the new session inside that call.
    While the gate is closed, `saveSession` is a no-op. The gate is open at process start, so a
    running mission keeps its refreshed tokens.

### 3.3 Device ID
`DeviceIdProvider(store)`:
- `deviceId()` returns `store.getOrCreateDeviceId { UUID.randomUUID().toString() }`. The value is a
  random v4 UUID created on first launch. IMEI and ANDROID_ID are never used.
- `deviceName()` returns `"${Build.MANUFACTURER} ${Build.MODEL}"`, trimmed to 100 characters. It is a
  constructor parameter so it can be replaced in tests.
- The ID survives logout. It does not survive an uninstall, and backup is disabled (section 2), so a
  restored install gets a new ID and the old one cannot be cloned.
- **When it is created (review R3):** `SessionBootstrap` calls `deviceId()` at every start-up, so the
  ID exists from the first launch. This call is best effort: on a full disk the write fails while
  reads still work, and a stored grant must still reach the mission screen. `LoginUseCase` still
  uses get-or-create.
- A corrupt session file is replaced with an empty one (section 3.1). The ID lives in that file and
  cannot be recovered from it, so it is regenerated (the backend then sees a new device).

### 3.4 AuthBackend (OSP-60 contract)
```kotlin
interface AuthBackend {
    suspend fun bindSession(accessToken: String, deviceId: String, deviceName: String): BackendResult<SessionResponse>
    suspend fun logout(accessToken: String): BackendResult<Unit>
}
sealed interface BackendResult<out T> { data class Ok<T>(val value: T) : BackendResult<T>
                                       data class Err(val error: BackendError) : BackendResult<Nothing> }
sealed interface BackendError { data class Http(val status: Int, val code: String?, val message: String?) : BackendError
                                data object Network : BackendError; data object Malformed : BackendError }
```
- `KtorAuthBackend` sends `POST {API_BASE_URL}/api/v1/auth/session` and `/api/v1/auth/logout` with
  `Authorization: Bearer <token>`.
- It uses `HttpClient(OkHttp)` from `HttpClientFactory`: JSON content negotiation with
  `ignoreUnknownKeys = true`, and `HttpTimeout` (connect 10 s, request 15 s).
- supabase-kt is given the same engine explicitly (`httpEngine = OkHttp.create()`), so both stacks
  are covered by the Network Security Config.
- **Fake (debug only):** `FakeAuthBackend` lives in `src/debug`, so the release APK does not contain
  the class.
  - The debug `AuthBackendFactory` returns it when `BuildConfig.USE_FAKE_BACKEND` is true. The
    default is `true` and it is overridable with `CONNECT_FAKE_BACKEND=false` in local.properties.
  - The release factory always returns `KtorAuthBackend`.
  - The fake decodes (without verifying) the JWT `sub` and `user_role` claims to imitate the
    backend: `user_role == null` returns 403 `ACCOUNT_DISABLED`; a rescuer gets a grant of now
    plus 72 h; other roles get no grant.
  - It adds a 300 ms delay, and its logout is a no-op.

### 3.5 DTOs (`AuthDtos.kt`, the only file that knows wire names)
```kotlin
@Serializable data class SessionRequest(@SerialName("device_id") val deviceId: String,
                                        @SerialName("device_name") val deviceName: String)
@Serializable data class SessionResponse(@SerialName("user_id") val userId: String, val role: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName(GRANT_JSON_KEY) val grant: OfflineMissionGrantDto? = null)
@Serializable data class OfflineMissionGrantDto(@SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("authenticated_at") val authenticatedAt: String,   // ISO-8601 with offset
    @SerialName("expires_at") val expiresAt: String)
@Serializable data class ErrorBody(@SerialName("error_code") val errorCode: String, val message: String)
/** ASSUMPTION — not specified in OSP-60; confirm with backend. */
const val GRANT_JSON_KEY = "offline_mission_grant"
```
- Timestamps are parsed with `OffsetDateTime.parse(...).toInstant()` (java.time is available on API 26+).
- **Client-side grant sanity check.** All of the following must hold, or the result is
  `GrantInvalid` and the teardown runs:
  - `grant.user_id == user_id`
  - `grant.device_id ==` our `device_id`
  - `0 < expires_at - authenticated_at ≤ 72 h + 60 s`
- Validity over time (expiry, monotonic clock) belongs to OSP-73.

### 3.6 Error mapping (`AuthError` to UI message; the full EN/TR text is in strings.xml)
| Source | AuthError | Teardown? | UI message (EN) |
|---|---|---|---|
| Blank e-mail or password, or bad e-mail format (client side) | `InvalidInput` | no | "Enter your e-mail and password." |
| Supabase `AuthRestException` `invalid_credentials` / `validation_failed` | `BadCredentials` | wipe local | "Incorrect e-mail or password." |
| Supabase `user_banned` | `AccountDisabled` | wipe local | "This account is disabled. Contact your administrator." |
| Supabase `email_not_confirmed` | `AccountNotReady` | wipe local | "This account is not activated yet. Contact your administrator." |
| Supabase `over_request_rate_limit` / HTTP 429; backend 429 `RATE_LIMITED` | `RateLimited` | yes | "Too many attempts. Wait a few minutes and try again." |
| `HttpRequestException` / `HttpRequestTimeoutException` / `IOException` (incl. a cleartext block) | `Network` | yes (if signed in) | "No connection. Field Rescuer login needs internet before deployment." |
| 200 with `role` ≠ `rescuer` (coordinator/administrator), or 403 `ROLE_FORBIDDEN` | `RoleNotAllowed` | **yes** | "Field Rescuer Mode is only for rescuer accounts. Coordinators and administrators must use the web dashboard." |
| 401 `UNAUTHENTICATED` | `SessionRejected` | yes | "Your session could not be verified. Please sign in again." |
| 403 `ACCOUNT_DISABLED` | `AccountDisabled` | yes | (as above) |
| 403 `DEVICE_REVOKED` | `DeviceRevoked` | yes | "This device has been revoked. Contact your administrator." |
| 400 `VALIDATION_ERROR` | `RequestRejected` | yes | "Login request was rejected. Update the app or contact your coordinator." |
| 200 rescuer with the grant missing or failing the sanity check, or an unparsable body | `GrantInvalid` | yes | "The server returned an invalid deployment grant. Try again or contact your coordinator." |
| 403 `SESSION_NOT_BOUND`, 404, 5xx `INTERNAL_ERROR`, unknown code | `Server` | yes | "Server error. Please try again later." |
| Missing config (SUPABASE_URL, SUPABASE_ANON_KEY, API_BASE_URL blank) | `NotConfigured` | n/a | "This build is not configured for online login." |

The server `message` is logged in debug builds only and never shown, to keep the UI text localised.

### 3.7 Config
- `app/build.gradle.kts` reads `local.properties` (which is in `.gitignore`) into `BuildConfig`
  fields: `SUPABASE_URL`, `SUPABASE_ANON_KEY` and `API_BASE_URL`.
  - Missing keys default to `""`, so the app builds without them and shows `NotConfigured`.
  - Nothing is hard-coded.
- `CONNECT_FAKE_BACKEND` is handled the same way and is **debug only**. The release `buildConfigField`
  is a literal `false`.
- `AppConfig` checks only that the values are non-blank. It deliberately has **no** `https://` scheme
  check: an `http://` URL must reach the OS and be refused by the Network Security Config, which is
  what the story asks to demonstrate (see section 7).

## 4. Login state machine and logout

### 4.1 States (`RescuerAuthViewModel.state: StateFlow<AuthUiState>`)
```
Bootstrapping ──grant present──────────────────────────────► MissionActive(grant)
     │        └─session w/o grant → teardown(local only) ─┐
     └─no grant ──────────────────────────────────────────┴► LoginIdle(error = null)
LoginIdle ──submit, input invalid──► LoginIdle(InvalidInput)
LoginIdle ──submit──► SigningIn ──Supabase error──► teardown(local) ► LoginIdle(mapped error)
                          │ ok
                          ▼
                     BindingSession ──role ≠ rescuer──────────► TearingDown ► LoginIdle(RoleNotAllowed)
                          │          ──401/403/429/400/5xx/net──► TearingDown ► LoginIdle(mapped error)
                          │          ──grant missing/invalid────► TearingDown ► LoginIdle(GrantInvalid)
                          │ 200 rescuer + valid grant
                          ▼
                     write grant (SessionStore) ──► MissionActive(grant)
MissionActive ──"End Mission / Log Out" → confirm dialog──► LoggingOut ──► LoginIdle(info = LoggedOut)
```
- Submit is disabled outside `LoginIdle`, so double taps are ignored.
- **Cancellation (review R6):** if the login coroutine is cancelled after the sign-in (for example the
  activity finishes during "Verifying deployment authorization…"), `LoginUseCase` runs the local
  teardown in `NonCancellable` and rethrows. A `CancellationException` while the coroutine is still
  active (a stray nested timeout) is treated as a `Server` failure with the full teardown.
- The password lives only in ViewModel memory and is cleared after each attempt.
- The JWT `user_role` claim is **not** used for decisions. Only `SessionResponse.role` is.
- Grant expiry and gating of `MissionActive` are not enforced here:
  `// TODO(OSP-73): OfflineGrantPolicy gate + BiometricPrompt`. Victim and Relay modes stay
  auth-free; nothing here blocks them.

### 4.2 Teardown / logout sequence (`LogoutUseCase.invoke(remote: Boolean)`)
Shared by "End Mission", role rejection, every post-sign-in failure, and the stale-session start-up
rule. (Order changed after review finding R1, approved 2026-11-19: before, the grant was deleted only
after up to ~15 s of remote calls, so a process death in that window kept offline access.)
1. **Immediately**, inside `withContext(NonCancellable)`: `store.clearGrant()` deletes the grant keys
   only. The grant is the offline-access gate. Removing it does not touch the in-memory Supabase
   session that the remote calls below still need, and the persisted tokens stay until step 4.
   - The grant is deleted *before* the token is read. Reading the token waits for the gateway
     initialisation (up to 5 s), and that wait must not keep the grant on disk.
2. If `remote` is true: `gateway.awaitInitialized()` (bounded, see section 3.2), then snapshot
   `token = gateway.accessTokenOrNull()` in memory. If a token exists:
   `withTimeoutOrNull(5 s) { backend.logout(token) }` (LOGOUT audit and binding clear). Every
   exception is caught.
3. If `remote` is true: `withTimeoutOrNull(5 s) { gateway.signOut() }` (Supabase `/logout`, scope
   LOCAL). Every exception is caught.
   - Finding: the `signOut()` in auth-kt 3.9.0 clears local data only on `RestException`.
   - On no network it throws `HttpRequestException` (an IOException) **before** clearing.
   - So it is never relied on for the wipe.
4. **Always**, in `finally` inside `withContext(NonCancellable)`, every step unconditional:
   - `gateway.awaitInitialized()`, then `gateway.clearLocalSession()`. It closes the save gate,
     stops auto-refresh, calls `auth.clearSession()` (which calls our `deleteSession()`), and stops
     auto-refresh again (section 3.2, R7). So the tokens cannot be saved again.
   - Then `store.clearAuth()`, which is the scoped removal of `{supabase_session, grant_*}`.
   - Each step catches `Throwable`, including a `CancellationException` thrown *by the callee*
     (review R9), so a failing or cancelling step can never skip `clearAuth()`.
   - `invoke` returns `false` only if `clearAuth()` itself failed (store error); the UI then shows
     the generic error instead of "logged out" (R4).
5. `device_id`, any other DataStore key and every other app file stay untouched.
   `LogoutUseCase` depends only on `SessionStore`, `AuthBackend` and `SupabaseAuthGateway`. It has
   no `Context` and no DB handle, so it cannot reach the future victim DB. This is tested.
6. Test: `backend.logout` reads the store and then throws (a stand-in for a process death during
   the remote part). The grant is already gone while the tokens are still present.

## 5. Screens (Compose, Material3, EN default + values-tr)
- **LoginScreen:**
  - Title "Field Rescuer login".
  - E-mail field (`KeyboardType.Email`) and password field (masked, with a show/hide toggle).
  - "Sign in" button with a progress indicator while `SigningIn` / `BindingSession`.
  - An error text from `AuthErrorText`, and the info "You have been logged out." after logout.
  - A note: "Online login is required before deployment."
  - `SupplementaryDisclaimer()` at the bottom.
- **MissionScreen:**
  - "Field Rescuer Mode active".
  - `stringResource(R.string.mission_authenticated_until, formatted)`:
    - EN: "Authenticated for offline deployment until %1$s"
    - TR: "Çevrimdışı görev için %1$s tarihine kadar yetkilendirildi"
  - `formatted` is `DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)`
    with `ZoneId.systemDefault()` and the current locale, plus the zone ID (for example
    "Europe/Istanbul") so the zone is never ambiguous.
  - An "End Mission / Log Out" button (EN) / "Görevi Bitir / Çıkış Yap" (TR), with a confirm
    dialog. The dialog warns that offline access ends until the next online login and says that
    unsynced victim records are kept.
  - `SupplementaryDisclaimer()`.
- **SupplementaryDisclaimer:**
  - A stateless `@Composable fun SupplementaryDisclaimer(modifier: Modifier = Modifier)` with an
    info icon and the `R.string.supplementary_disclaimer` text.
  - The EN and TR texts are exactly (SoW NFR-SAFE-03):
    - EN: "CONNECT is a supplementary channel and does not guarantee rescue. The absence of records must never be used to terminate search operations."
    - TR: "CONNECT yardımcı bir kanaldır ve kurtarmayı garanti etmez. Kayıt bulunmaması, arama çalışmalarını sonlandırmak için asla gerekçe olarak kullanılmamalıdır."
  - The composable is in `ui/common` so that other modes can reuse it.

## 6. Implementation checkpoints
The first command runs from the repo root. Each later checkpoint must also keep the previous ones
green. **Per section 0 item 8, every checkpoint is verified with the full command**
`timeout 1500 ./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug assembleDebugAndroidTest`
(1.6 adds `testReleaseUnitTest`); the column below lists what each checkpoint adds.

| # | Content | Verification |
|---|---|---|
| 1.1 | `.gitignore`, wrapper (`gradle wrapper --gradle-version 9.7.1 --gradle-distribution-sha256-sum <official sha256>` run with the cached 9.7.1 binary), version catalog, `app` module, empty Compose `MainActivity`, manifest, `network_security_config.xml`, `data_extraction_rules.xml`, BuildConfig fields | `timeout 1500 ./gradlew assembleDebug assembleRelease lintDebug` and `git check-ignore local.properties` |
| 1.2 | `SessionStore`, `DataStoreSessionStore`, `StoreBackedSessionManager`, `DeviceIdProvider` and their tests | `timeout 1500 ./gradlew testDebugUnitTest` |
| 1.3 | `HttpClientFactory`, DTOs, `AuthBackend`, `KtorAuthBackend`, debug `FakeAuthBackend` and factories (debug and release), `SupabaseAuthGateway` and its supabase-kt impl, `AppContainer` | `timeout 1500 ./gradlew testDebugUnitTest assembleRelease` |
| 1.4 | `AuthError`, `LoginUseCase`, `LogoutUseCase`, `SessionBootstrap`, `RescuerAuthViewModel` | `timeout 1500 ./gradlew testDebugUnitTest` |
| 1.5 | Screens, theme, `SupplementaryDisclaimer`, EN/TR strings (lint `MissingTranslation` enforces parity) | `timeout 1500 ./gradlew assembleDebug assembleRelease lintDebug` |
| 1.6 | Remaining tests, `CleartextPolicyTest` (instrumented), final run | `timeout 1500 ./gradlew assembleDebug assembleRelease testDebugUnitTest testReleaseUnitTest lintDebug assembleDebugAndroidTest` |

Commit after each checkpoint (`OSP-74: …`), staging explicit paths only (never `local.properties`).

## 7. Test plan

**JVM unit tests (`app/src/test`), one line per class:**
- `DataStoreSessionStoreTest` (temp file): grant round-trip; `clearAuth()` removes session + grant keys but **keeps** `device_id`, a foreign sentinel key and the file itself.
- `DeviceIdProviderTest`: created once, stable across store instances, valid v4 UUID, 20 concurrent calls return one value.
- `StoreBackedSessionManagerTest`: `UserSession` round-trip; empty load throws `NoSessionFoundException`; `deleteSession` keeps grant and `device_id`.
- `KtorAuthBackendTest` (`MockEngine`): path, Bearer header, JSON body; 200 rescuer+grant and 200 coordinator parsed; every section 3.6 `error_code`; non-JSON/unknown error body; `IOException` maps to `Network`.
- `LoginUseCaseTest`: rescuer stores grant (sends `device_id`, `device_name`); coordinator/administrator/`ROLE_FORBIDDEN` give `RoleNotAllowed` with full teardown in order (backend logout, signOut, clearLocalSession, clearAuth); 401/403s/429/400/5xx mapped with teardown; bad/over-72 h/missing grant gives `GrantInvalid`; `invalid_credentials` gives `BadCredentials` with nothing stored.
- `LogoutUseCaseTest`: backend throwing/hanging/no token and `signOut()` throwing `HttpRequestException` still wipe locally; `/auth/logout` runs before `signOut()`; `device_id`, a sentinel DataStore key and a sentinel "victim DB" file are untouched; caller cancellation mid-logout still completes the wipe; gateway initialisation is awaited before clearing (fake gateway whose init completes late).
- `SessionBootstrapTest`: grant leads to `MissionActive`; session without grant is wiped and leads to `LoginIdle`; empty store leads to `LoginIdle`.
- `RescuerAuthViewModelTest` (`Dispatchers.setMain`): section 4.1 transitions; password cleared; double submit ignored.
- `AuthErrorTextTest` (every `AuthError` has a string); `ExpiryFormatterTest` (fixed Instant, `Europe/Istanbul`, EN/TR locales).
- `NetworkSecurityConfigTest`: static guard that the XML has `cleartextTrafficPermitted="false"` and no `<domain-config>` / `<debug-overrides>`.
- `src/testDebug/FakeAuthBackendTest`: role from JWT claim, `user_role == null` gives 403, rescuer gets a 72 h grant.

**OS-level cleartext proof (unit tests cannot prove it):**
- `androidTest/CleartextPolicyTest`:
  - asserts `NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted` is `false`, including
    for `10.0.2.2`;
  - sends a GET to `http://example.com/` with the app's own `HttpClientFactory` client;
  - expects the call to fail with an exception whose **cause chain** contains a message with
    "CLEARTEXT communication" / "not permitted" (OkHttp raises `UnknownServiceException` before any
    socket is opened; Ktor may wrap it, so the test does not assert the exact class).
- In this environment there is **no emulator and no system image**, and `adb devices` is empty. The
  test is only *compiled* here (`assembleDebugAndroidTest`).
- To run it, connect a physical device or create an emulator and run
  `timeout 1500 ./gradlew connectedDebugAndroidTest`.

**Manual checks (on a device):**
1. Fresh install, then log in as a rescuer (fake backend or a real one). The Mission screen shows
   the local time 72 h ahead, and the disclaimer appears on both screens.
2. Log in as a coordinator or administrator. The role error appears, it stays on the login screen,
   and after relaunch it is still on the login screen (tokens wiped).
3. Airplane mode, then End Mission. Logout succeeds and the login screen shows "logged out".
   `run-as com.connect.mobile cat files/datastore/connect_session.preferences_pb` still shows only
   the `device_id` key.
4. Cleartext:
   - set `API_BASE_URL=http://10.0.2.2:3000` in local.properties for a debug build with
     `CONNECT_FAKE_BACKEND=false` (no code change needed: `AppConfig` has no scheme check);
   - log in;
   - `adb logcat` shows `CLEARTEXT communication to 10.0.2.2 not permitted by network security
     policy`, and the UI shows the `Network` error.

**Developer testing without HTTP:**
- A local backend on `http://10.0.2.2` **and** a local Supabase CLI stack (`http://127.0.0.1:54321`)
  are both blocked by design.
- Options:
  - (a) the debug `FakeAuthBackend` with the hosted Supabase project (HTTPS);
  - (b) an HTTPS tunnel to a local backend, for example `cloudflared tunnel --url http://localhost:3000`
    or `ngrok http 3000`, with `API_BASE_URL` set to the https tunnel URL.
- No `debug-overrides` or domain exceptions are added.

## 8. Open questions and assumptions
Items 1–4 are now tracked in `docs/osp-60-contract-assumptions.md` and at each code site by the
`// OSP-60 ASSUMPTION:` comment. Items 5–7 were decided by the user on 2026-11-19 (section 0):
5 = stay on 37 / BOM 2026.09.00, 6 = no fake Supabase sign-in, 7 = tests on the user's phone later.

1. **Grant JSON key**: the assumed name is `offline_mission_grant` (the `GRANT_JSON_KEY` constant in
   `AuthDtos.kt`). Confirm with the OSP-60 backend owner.
2. **Timestamp format**: ISO-8601 with an offset (`2026-11-19T18:00:00+00:00` or `…Z`) is assumed
   for `authenticated_at` and `expires_at`.
3. **`/auth/logout` request**: a Bearer header and no body are assumed, and an expired access token
   is acceptable (best effort anyway). The client also calls it after a **role rejection** (the backend
   has already bound the session), so a rejected coordinator/administrator produces a LOGOUT audit
   entry. Confirm with the backend owner that this is acceptable.
4. **Response shapes:**
   - Does `/auth/session` return 403 `ROLE_FORBIDDEN` instead of 200 for coordinators? Both are
     handled.
   - Is an inactive profile reported as 403 `ACCOUNT_DISABLED`? Assumed yes.
5. **Compose BOM pinned to 2026.09.00** because 2026.11.01 needs compileSdk 37.1. Installing
   `platforms;android-37.1` later would allow the upgrade. This is the user's decision; nothing is
   installed now.
6. **Debug fake Supabase sign-in**: not provided. Debug builds still need a reachable HTTPS Supabase
   project (`SUPABASE_URL` and `SUPABASE_ANON_KEY` in local.properties) to log in. Should a debug-only
   fake gateway be added for fully offline UI demos? It is out of scope unless requested.
7. **No running device or emulator here**: the cleartext instrumented test and the manual checks
   need the user's phone (USB debugging) or an emulator image the user installs.
8. **Release signing and R8**: out of scope. Release stays unsigned with `isMinifyEnabled = false`
   (`assembleRelease` only checks that it compiles).
