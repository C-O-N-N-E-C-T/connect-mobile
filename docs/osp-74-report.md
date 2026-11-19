# OSP-74 Work Report / Çalışma Raporu

Jira story OSP-74 (CONNECT project, epic OSP-56 / E10): Android scaffold and Field Rescuer pre-deployment login.
Repository: `connect-mobile`, branch `OSP-74`. Date: 2026-11-19.

[English](#english) · [Türkçe](#türkçe)

---

## English

### 1. Summary
CONNECT is an offline-first search-and-rescue system for earthquakes. OSP-74 creates the Android app
(Kotlin, Jetpack Compose) and implements its first feature: the **login of a Field Rescuer before
going on a mission**. After one online login, the app holds a **72-hour offline mission grant**, so
the rescuer can work without internet afterwards.

What the story delivers:
- A new Android project (single `app` module, applicationId `com.connect.mobile`) with a pinned
  Gradle wrapper.
- E-mail and password sign-in through Supabase Auth (library supabase-kt), followed by a call to
  `POST /api/v1/auth/session` with a random per-install device ID.
- Only the `rescuer` role is accepted.
- Session and grant are stored behind a storage interface. The screen shows "Authenticated for
  offline deployment until <time>" and the supplementary-channel disclaimer.
- Network traffic is HTTPS only.
- "End Mission / Log Out" deletes the grant and tokens but keeps unsynced victim records.

**Status:** code complete and verified by build, unit tests, lint and an independent review. **It has
not yet been run on a real phone or emulator**, and nothing has been pushed. The backend endpoints
(OSP-60) do not exist yet, so debug builds use a fake backend (section 5).

### 2. Scope versus acceptance criteria
Statement of Work (SoW) references: FR-R-15, NFR-SEC-02, NFR-SAFE-03, AC-19, AC-29.

| Requirement | How it is implemented | Verification / status |
|---|---|---|
| Sign in with e-mail and password via supabase-kt | `SupabaseKtAuthGateway`, `LoginUseCase`, login screen | Unit tests with a fake gateway; real Supabase sign-in not yet run on a device |
| Random install UUID as `device_id` (never IMEI/ANDROID_ID) | `DeviceIdProvider`, created at app start-up, atomic, kept across logouts | Unit tests: stable, valid v4 UUID, 20 concurrent calls give one value |
| Then `POST /api/v1/auth/session` | `KtorAuthBackend` (Bearer token, `device_id`, `device_name`) | Unit tests with a mock HTTP engine; real endpoint pending OSP-60 |
| Only role `rescuer` | Decision on the backend's `role` field; other roles see a role error and are logged out at once | Unit tests (coordinator, administrator, `ROLE_FORBIDDEN`) |
| Persist session and 72 h grant via a storage interface | `SessionStore`, temporary `DataStoreSessionStore` | Unit tests; encrypted version is OSP-75 |
| Show "Authenticated for offline deployment until <expires_at>" and disclaimer (NFR-SAFE-03, AC-29) | `MissionScreen`, reusable `SupplementaryDisclaimer` (SoW wording), English and Turkish | Unit tests for text/formatting; screens never rendered yet |
| HTTPS only (NFR-SEC-02) | `network_security_config.xml` with `cleartextTrafficPermitted="false"`, no exceptions, also in debug | JVM test of the XML; `CleartextPolicyTest` (on device) compiled, **not run** |
| End Mission / Log Out wipes grant and tokens, keeps unsynced victim records | `LogoutUseCase`: scoped wipe only; no access to any victim database | Unit tests with sentinel keys and a sentinel "victim DB" file |

### 3. Architecture overview
Manual constructor injection through one `AppContainer` (no DI framework: about ten objects).

| Area | Main files (`app/src/main/java/com/connect/mobile/`) |
|---|---|
| Configuration | `config/AppConfig.kt` (reads `local.properties` through BuildConfig) |
| Storage | `data/session/SessionStore.kt`, `DataStoreSessionStore.kt`, `StoreBackedSessionManager.kt` |
| Device ID | `data/device/DeviceIdProvider.kt` |
| Network | `data/network/HttpClientFactory.kt` (Ktor + OkHttp, the only HTTP stack) |
| Backend (OSP-60) client | `data/backend/AuthBackend.kt`, `AuthDtos.kt`, `KtorAuthBackend.kt` |
| Supabase | `data/supabase/SupabaseAuthGateway.kt`, `SupabaseKtAuthGateway.kt`, `LazyAuthGateway.kt` |
| Logic | `auth/LoginUseCase.kt`, `LogoutUseCase.kt`, `SessionBootstrap.kt`, `AuthError.kt` |
| UI | `ui/RescuerAuthViewModel.kt`, `login/LoginScreen.kt`, `mission/MissionScreen.kt`, `common/SupplementaryDisclaimer.kt` |
| Debug only | `app/src/debug/.../FakeAuthBackend.kt` (not in release) |

**Login flow, in plain words**
1. At start-up the app creates (once) the install device ID. If a stored grant exists, it goes
   straight to the mission screen; a stored session without a grant is wiped.
2. The rescuer enters e-mail and password. Supabase checks them.
3. The app sends the Supabase token and the device ID to `POST /api/v1/auth/session`.
4. If the role is not `rescuer`, or the answer is invalid, the app shows an error and logs out.
5. The app checks the answer: user and device must match the sign-in, the grant must last more than
   0 and at most 72 hours, and the phone clock must not be more than 5 minutes behind the server.
6. Session and grant are saved; the mission screen shows the expiry time and the disclaimer.
7. If the phone clock passes `expires_at`, the screen says the authorization has expired and asks the
   user to connect and log in again. Only End Mission / Log Out remains. Nothing is deleted automatically.

**Logout flow (order matters)**
1. The **grant is deleted first**, so offline access ends even if the app is killed mid-logout.
2. Best-effort remote calls with 5-second limits: backend `/auth/logout`, then Supabase sign-out.
3. Always, whatever happened above: a scoped wipe of the session tokens and grant keys.
4. `device_id`, other settings and the victim records are never touched. The logout code has no access
   to the future victim database.

### 4. Decisions and rationale (2026-11-19)

| Decision | Rationale |
|---|---|
| applicationId `com.connect.mobile` | Single module, simple, matches the package |
| minSdk 26 (Android 8+); compileSdk/targetSdk 37.0 | The libraries need minSdk of 24 or lower, so older phones work. compileSdk does not limit which devices can run the app |
| Compose BOM 2026.09.00 | The newer BOM (2026.11.01) needs compileSdk 37.1, which is not installed |
| Gradle 9.7.1 wrapper with `distributionSha256Sum`; JDK 21 toolchain; no system-wide Gradle/Kotlin | Team rule: same tools for everyone, verified download |
| Storage behind `SessionStore`, temporary plaintext DataStore | OSP-75 will replace it with Tink AEAD encryption. **Known temporary security limitation** (section 7) |
| supabase-kt's own plaintext session storage replaced by our session manager | Avoids a second, unprotected copy of the tokens |
| Debug-only fake backend, 15 `// OSP-60 ASSUMPTION` markers, handoff document | OSP-60 is not implemented yet; the assumptions stay visible and searchable |
| Disclaimer wording taken from SoW NFR-SAFE-03 | Requirement text, not our own wording |
| UI in English and Turkish | Users and reviewers are Turkish-speaking |
| Logout order: grant first, remote calls best-effort, then scoped wipe, `device_id` kept | Found in review (R1): offline access must end even on process death |
| Minimal wall-clock expiry check with an "expired" state; monotonic clock and biometrics stay in OSP-73 | Found in review (R2): an expired grant must not look active |
| Login rejected if the phone clock is more than 5 minutes behind the server | Skew guard against a wrong clock extending the grant |

### 5. Dependencies on other cards

| Card | Relation | What its owner needs to do |
|---|---|---|
| OSP-60 (backend `/auth/session`, `/auth/logout`) | Not implemented yet; the app assumes a contract | Backend owner: read `docs/osp-60-contract-assumptions.md` (response format, grant key `offline_mission_grant`, ISO-8601 timestamps with offset, error codes, logout after role rejection) and confirm or correct it. List all code sites with `grep -rn "OSP-60 ASSUMPTION" app/src` |
| OSP-75 (encrypted storage) | Replaces the temporary plaintext DataStore | Owner: implement `SessionStore` with Tink AEAD; no UI/login code should change |
| OSP-73 (grant policy, biometrics) | Builds on the minimal expiry check | Add the monotonic-clock policy and biometric lock at the `TODO(OSP-73)` seam |
| OSP-69 | Web dashboard login page and the web `<SupplementaryDisclaimer />` component (NFR-SAFE-03) | No code dependency. The Android `SupplementaryDisclaimer` composable is the mobile counterpart; both should show the same NFR-SAFE-03 wording, so a wording change must be applied in both repos |
| OSP-76 (reconnection) | Later story | Seams are prepared; not built |

### 6. Quality
Final verification, clean build (`--rerun-tasks`): `assembleDebug`, `assembleRelease`,
`testDebugUnitTest`, `testReleaseUnitTest`, `lintDebug` and `assembleDebugAndroidTest` all pass.

| Check | Result |
|---|---|
| Unit tests, debug | 89 of 89 passed |
| Unit tests, release | 86 of 86 passed (proves `src/test` does not need the debug fake) |
| Lint | 0 errors, 8 warnings: 7 deliberate version pins (compatibility with compileSdk 37.0), 1 missing app icon |
| Fake backend | Present in the debug APK, absent from the release APK (checked in the dex) |

**Independent review** (a separate reviewer, findings R1 to R18): 0 Critical, 0 High, 2 Medium,
11 Low, 5 Nit. All were fixed except **R13**, a proposed "DEBUG: fake backend" banner, which the
team owner declined.

| Finding | Issue | Fix |
|---|---|---|
| R1 (Medium) | Grant was deleted only after remote calls | Grant deleted first; wipe unconditional |
| R2 (Medium) | Expired grant still showed "authenticated until" | Wall-clock check and expired state |
| R3-R12 (Low) | Device ID created late; store errors could crash; user/device IDs in the response not compared; client created eagerly; cancellation edge cases; config errors; test gaps | Fixed, with added tests |
| R14-R18 (Nit) | `.env` not ignored, test guard, error mapping, log detail, UI details | Fixed |
| R13 (Low) | No banner in debug fake mode | Declined by the owner; release is not affected |

### 7. Known limitations and open items
- **Not run on a device or emulator.** The screens were never rendered, and `CleartextPolicyTest` was
  compiled but not run. Testing on a team member's phone is the next step.
- **Temporary plaintext storage** of tokens and grant until OSP-75. Do not use with real operational data.
- OSP-60 contract assumptions need confirmation by the backend owner.
- Monotonic clock and biometric lock are not implemented (OSP-73).
- No app icon (one lint warning). Release is unsigned and not minified.
- Nothing has been pushed yet.
- One review item (R7, a possible race inside supabase-kt) could not be proven either way; it is mitigated by stopping auto-refresh again after clearing.

### 8. How to build, configure and test
Full details are in [README.md](../README.md). In short:
1. Install JDK 21 and Android SDK platform 37.0. Do not install Gradle; use `./gradlew`.
2. Create `local.properties` (never commit it; the repository is public) with `sdk.dir`,
   `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `API_BASE_URL`, and optionally `CONNECT_FAKE_BACKEND`.
3. Check: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug assembleDebugAndroidTest`.
4. On a phone: `./gradlew installDebug`, then `./gradlew connectedDebugAndroidTest` for the cleartext test.

Manual phone test steps:
1. Fresh install, log in as a rescuer: the mission screen shows a time 72 h ahead, and the disclaimer
   appears on both screens.
2. Log in as a coordinator or administrator: a role error appears; after relaunch the login screen is still shown.
3. Airplane mode, then End Mission: logout works and the login screen says "logged out"; the stored
   file keeps only `device_id`.
4. Cleartext: set `API_BASE_URL=http://...` with `CONNECT_FAKE_BACKEND=false`, log in; the UI shows
   "No connection" and `adb logcat -s CONNECT` shows "CLEARTEXT communication ... not permitted".

Local `http://` servers do not work by design; use an HTTPS tunnel or the debug fake backend.

### 9. Commits (oldest first)

| Commit | Message |
|---|---|
| 264dde0 | add design doc for Android rescuer login |
| dfc3d8a | apply user-approved changes to design doc |
| 8c9abe1 | scaffold app module, Gradle 9.7.1 wrapper and HTTPS-only network config |
| 0a09cb4 | add SessionStore (temporary DataStore impl), supabase session manager and install device id |
| 42f25ae | add auth backend client, debug fake backend, Supabase gateway and AppContainer |
| 49bbb22 | add login/logout use cases, start-up bootstrap and auth ViewModel |
| 10228dc | add login and mission screens, supplementary disclaimer and EN/TR strings |
| f78043e | add cleartext policy tests, OSP-60 contract handoff note and bilingual README |
| 3cec8c6 | silence supabase-kt logger, complete Turkish docs and document Supabase prerequisite |
| 565988b | fix R1 R9 delete grant before remote logout calls, unconditional wipe |
| 669b56a | fix R7 block session re-persist after logout |
| e91e8a0 | fix R3 R4 R6 device id at start-up, store errors, login cancellation |
| ec98bf3 | fix R2 wall-clock grant check and expired mission state |
| 4fa293a | fix R5 check response user_id and device_id against the sign-in |
| 0984d95 | fix R8 R11 create the Supabase client on first use, catch any config error |
| 04a5867 | fix R12 add missing gateway, timeout and edge-case tests |
| d67002b | fix R10 R14 R15 R16 R17 R18 review nits |
| 78f13e6 | fix R4 contain store errors in the supabase-kt session manager |

(All messages carry the prefix `OSP-74: `.) Total change since the starting point `8a486ef`: 68 files, about 4,900 added lines.

---

## Türkçe

### 1. Özet
CONNECT, depremler için çevrimdışı çalışmayı önceleyen bir arama-kurtarma sistemidir. OSP-74, Android
uygulamasını (Kotlin, Jetpack Compose) oluşturur ve ilk özelliğini geliştirir: **Saha Kurtarıcısının
göreve çıkmadan önceki girişi**. Kurtarıcı bir kez çevrimiçi giriş yaptıktan sonra uygulama **72
saatlik çevrimdışı görev yetkisini (grant)** saklar; böylece internet olmadan çalışılabilir.

Hikâyenin teslim ettikleri:
- Yeni bir Android projesi (tek `app` modülü, applicationId `com.connect.mobile`) ve sabitlenmiş Gradle wrapper.
- Supabase Auth (supabase-kt kütüphanesi) ile e-posta ve parola girişi; ardından rastgele, kuruluma
  özgü cihaz kimliğiyle `POST /api/v1/auth/session` çağrısı.
- Yalnızca `rescuer` rolü kabul edilir.
- Oturum ve grant bir depolama arayüzünün arkasında saklanır. Ekran "Çevrimdışı görev için <zaman>
  tarihine kadar yetkilendirildi" metnini ve yardımcı kanal uyarısını gösterir.
- Ağ trafiği yalnızca HTTPS'tir.
- "Görevi Bitir / Çıkış Yap" grant'i ve token'ları siler, senkronize edilmemiş kazazede kayıtlarını korur.

**Durum:** kod tamam; derleme, birim testleri, lint ve bağımsız inceleme ile doğrulandı. **Henüz gerçek
telefonda veya emülatörde çalıştırılmadı**, hiçbir şey push edilmedi. Backend uç noktaları (OSP-60)
henüz yok; bu yüzden debug derlemeleri sahte bir backend kullanır (bölüm 5).

### 2. Kapsam ve kabul ölçütleri
İş Tanımı (SoW) referansları: FR-R-15, NFR-SEC-02, NFR-SAFE-03, AC-19, AC-29.

| Gereksinim | Nasıl uygulandı | Doğrulama / durum |
|---|---|---|
| supabase-kt ile e-posta ve parola girişi | `SupabaseKtAuthGateway`, `LoginUseCase`, giriş ekranı | Sahte geçitle birim testleri; gerçek Supabase girişi cihazda henüz denenmedi |
| `device_id` olarak rastgele kurulum UUID'si (IMEI/ANDROID_ID asla) | `DeviceIdProvider`; uygulama açılışında üretilir, atomiktir, çıkışlarda korunur | Birim testleri: kararlı, geçerli v4 UUID, 20 eşzamanlı çağrı tek değer verir |
| Ardından `POST /api/v1/auth/session` | `KtorAuthBackend` (Bearer token, `device_id`, `device_name`) | Sahte HTTP motoruyla birim testleri; gerçek uç nokta OSP-60'a bağlı |
| Yalnızca `rescuer` rolü | Backend'in `role` alanına göre karar; diğer roller rol hatası görür ve hemen çıkış yaptırılır | Birim testleri (coordinator, administrator, `ROLE_FORBIDDEN`) |
| Oturum ve 72 saatlik grant'in depolama arayüzüyle saklanması | `SessionStore`, geçici `DataStoreSessionStore` | Birim testleri; şifreli sürüm OSP-75'te |
| "Çevrimdışı görev için <expires_at> tarihine kadar yetkilendirildi" ve uyarı (NFR-SAFE-03, AC-29) | `MissionScreen`, yeniden kullanılabilir `SupplementaryDisclaimer` (SoW metni), İngilizce ve Türkçe | Metin/biçim birim testleri; ekranlar henüz çizdirilmedi |
| Yalnızca HTTPS (NFR-SEC-02) | `network_security_config.xml`, `cleartextTrafficPermitted="false"`, istisna yok, debug'da da | XML için JVM testi; `CleartextPolicyTest` (cihazda) derlendi, **çalıştırılmadı** |
| Görevi Bitir / Çıkış Yap: grant ve token'lar silinir, senkronize edilmemiş kazazede kayıtları kalır | `LogoutUseCase`: yalnızca kapsamlı silme; hiçbir kazazede veritabanına erişimi yok | Nöbetçi anahtar ve nöbetçi "kazazede DB" dosyasıyla birim testleri |

### 3. Mimari genel bakış
Tek bir `AppContainer` üzerinden elle bağımlılık enjeksiyonu (DI framework yok: yaklaşık on nesne).

| Alan | Ana dosyalar (`app/src/main/java/com/connect/mobile/`) |
|---|---|
| Yapılandırma | `config/AppConfig.kt` (`local.properties` değerlerini BuildConfig ile okur) |
| Depolama | `data/session/SessionStore.kt`, `DataStoreSessionStore.kt`, `StoreBackedSessionManager.kt` |
| Cihaz kimliği | `data/device/DeviceIdProvider.kt` |
| Ağ | `data/network/HttpClientFactory.kt` (Ktor + OkHttp, tek HTTP yığını) |
| Backend (OSP-60) istemcisi | `data/backend/AuthBackend.kt`, `AuthDtos.kt`, `KtorAuthBackend.kt` |
| Supabase | `data/supabase/SupabaseAuthGateway.kt`, `SupabaseKtAuthGateway.kt`, `LazyAuthGateway.kt` |
| Mantık | `auth/LoginUseCase.kt`, `LogoutUseCase.kt`, `SessionBootstrap.kt`, `AuthError.kt` |
| Arayüz | `ui/RescuerAuthViewModel.kt`, `login/LoginScreen.kt`, `mission/MissionScreen.kt`, `common/SupplementaryDisclaimer.kt` |
| Yalnızca debug | `app/src/debug/.../FakeAuthBackend.kt` (release'de yok) |

**Giriş akışı, sade anlatımla**
1. Açılışta uygulama kurulum cihaz kimliğini (bir kez) üretir. Kayıtlı grant varsa doğrudan görev
   ekranına gider; grant'siz kayıtlı oturum silinir.
2. Kurtarıcı e-posta ve parolayı girer. Supabase doğrular.
3. Uygulama Supabase token'ını ve cihaz kimliğini `POST /api/v1/auth/session` uç noktasına gönderir.
4. Rol `rescuer` değilse veya yanıt geçersizse hata gösterilir ve çıkış yaptırılır.
5. Uygulama yanıtı denetler: kullanıcı ve cihaz girişle eşleşmeli, grant süresi 0'dan büyük ve en
   fazla 72 saat olmalı, telefon saati sunucudan 5 dakikadan fazla geride olmamalıdır.
6. Oturum ve grant kaydedilir; görev ekranı bitiş zamanını ve uyarıyı gösterir.
7. Telefon saati `expires_at` değerini geçerse ekran yetkinin süresinin dolduğunu söyler ve internete
   bağlanıp yeniden giriş yapmayı ister. Yalnızca Görevi Bitir / Çıkış Yap kalır. Otomatik silme yapılmaz.

**Çıkış akışı (sıra önemlidir)**
1. **Önce grant silinir**; uygulama çıkış sırasında kapansa bile çevrimdışı erişim biter.
2. 5 saniyelik sınırlarla, başarısı garanti olmayan uzak çağrılar: backend `/auth/logout`, sonra
   Supabase çıkışı.
3. Yukarıda ne olursa olsun her zaman: oturum token'larının ve grant anahtarlarının kapsamlı silinmesi.
4. `device_id`, diğer ayarlar ve kazazede kayıtlarına dokunulmaz. Çıkış kodunun ileride gelecek
   kazazede veritabanına erişimi yoktur.

### 4. Kararlar ve gerekçeler (2026-11-19)

| Karar | Gerekçe |
|---|---|
| applicationId `com.connect.mobile` | Tek modül, basit, paket adıyla uyumlu |
| minSdk 26 (Android 8+); compileSdk/targetSdk 37.0 | Kütüphanelerin minSdk değeri 24 veya altı olduğundan eski telefonlar desteklenir. compileSdk, uygulamanın çalışacağı cihazları sınırlamaz |
| Compose BOM 2026.09.00 | Daha yeni BOM (2026.11.01) kurulu olmayan compileSdk 37.1'i gerektirir |
| Gradle 9.7.1 wrapper + `distributionSha256Sum`; JDK 21 toolchain; sistem genelinde Gradle/Kotlin yok | Takım kuralı: herkes aynı araçları kullanır, indirme doğrulanır |
| Depolama `SessionStore` arkasında, geçici düz metin DataStore | OSP-75 bunu Tink AEAD şifrelemesiyle değiştirecek. **Bilinen geçici güvenlik sınırlaması** (bölüm 7) |
| supabase-kt'nin kendi düz metin oturum saklaması yerine bizim oturum yöneticimiz | Token'ların korumasız ikinci kopyasını önler |
| Yalnızca debug sahte backend, 15 adet `// OSP-60 ASSUMPTION` işareti, devir belgesi | OSP-60 henüz yok; varsayımlar görünür ve aranabilir kalır |
| Uyarı metni SoW NFR-SAFE-03'ten alındı | Gereksinim metni, bizim ifademiz değil |
| Arayüz İngilizce ve Türkçe | Kullanıcılar ve değerlendiriciler Türkçe konuşuyor |
| Çıkış sırası: önce grant, sonra uzak çağrılar, sonra kapsamlı silme; `device_id` korunur | İncelemede bulundu (R1): süreç ölse bile çevrimdışı erişim bitmeli |
| Asgari duvar saati süre kontrolü ve "süresi doldu" durumu; monoton saat ve biyometri OSP-73'te | İncelemede bulundu (R2): süresi dolmuş grant aktif görünmemeli |
| Telefon saati sunucudan 5 dakikadan fazla geriyse giriş reddedilir | Yanlış saatin grant'i uzatmasına karşı koruma |

### 5. Diğer kartlara bağımlılıklar

| Kart | İlişki | Sahibinin yapması gereken |
|---|---|---|
| OSP-60 (backend `/auth/session`, `/auth/logout`) | Henüz yok; uygulama bir sözleşme varsayıyor | Backend sahibi: `docs/osp-60-contract-assumptions.md` dosyasını okuyup (yanıt biçimi, grant anahtarı `offline_mission_grant`, ofsetli ISO-8601 zaman damgaları, hata kodları, rol reddinden sonra logout) onaylamalı veya düzeltmeli. Kod noktaları: `grep -rn "OSP-60 ASSUMPTION" app/src` |
| OSP-75 (şifreli depolama) | Geçici düz metin DataStore'un yerini alır | Sahibi: `SessionStore`'u Tink AEAD ile uygulamalı; giriş/arayüz kodu değişmemeli |
| OSP-73 (grant politikası, biyometri) | Asgari süre kontrolünün üzerine kurulur | `TODO(OSP-73)` noktasına monoton saat politikasını ve biyometrik kilidi eklemeli |
| OSP-69 | Web paneli giriş sayfası ve web `<SupplementaryDisclaimer />` bileşeni (NFR-SAFE-03) | Kod bağımlılığı yok. Android'deki `SupplementaryDisclaimer` composable'ı bunun mobil karşılığıdır; ikisi de aynı NFR-SAFE-03 metnini göstermeli, metin değişirse iki repoda birden güncellenmeli |
| OSP-76 (yeniden bağlanma) | Sonraki hikâye | Bağlantı noktaları hazır; yapılmadı |

### 6. Kalite
Son doğrulama, temiz derleme (`--rerun-tasks`): `assembleDebug`, `assembleRelease`,
`testDebugUnitTest`, `testReleaseUnitTest`, `lintDebug` ve `assembleDebugAndroidTest` hepsi başarılı.

| Kontrol | Sonuç |
|---|---|
| Birim testleri, debug | 89 / 89 geçti |
| Birim testleri, release | 86 / 86 geçti (`src/test`'in debug sahte backend'ine bağlı olmadığını kanıtlar) |
| Lint | 0 hata, 8 uyarı: 7 bilinçli sürüm sabitlemesi (compileSdk 37.0 uyumu), 1 eksik uygulama simgesi |
| Sahte backend | Debug APK'da var, release APK'da yok (dex içinde kontrol edildi) |

**Bağımsız inceleme** (ayrı bir inceleyici, bulgular R1-R18): 0 Kritik, 0 Yüksek, 2 Orta, 11 Düşük,
5 Nit. **R13** hariç hepsi düzeltildi; R13, önerilen "DEBUG: sahte backend" bannerıdır ve takım sahibi
tarafından reddedildi.

| Bulgu | Sorun | Çözüm |
|---|---|---|
| R1 (Orta) | Grant, uzak çağrılardan sonra siliniyordu | Önce grant silinir; silme koşulsuz |
| R2 (Orta) | Süresi dolmuş grant hâlâ "yetkili" görünüyordu | Duvar saati kontrolü ve "süresi doldu" durumu |
| R3-R12 (Düşük) | Cihaz kimliği geç üretiliyordu; depo hataları çökertebilirdi; yanıttaki kullanıcı/cihaz kimliği karşılaştırılmıyordu; istemci erken oluşuyordu; iptal kenar durumları; yapılandırma hataları; test boşlukları | Düzeltildi, testler eklendi |
| R14-R18 (Nit) | `.env` yok sayılmıyordu, test koruması, hata eşleme, log ayrıntısı, arayüz ayrıntıları | Düzeltildi |
| R13 (Düşük) | Debug sahte modda banner yok | Sahibi reddetti; release etkilenmez |

### 7. Bilinen sınırlamalar ve açık işler
- **Cihazda veya emülatörde çalıştırılmadı.** Ekranlar hiç çizdirilmedi, `CleartextPolicyTest` derlendi
  ama çalıştırılmadı. Sıradaki adım bir takım üyesinin telefonunda test etmektir.
- OSP-75'e kadar token ve grant **geçici olarak düz metin** saklanır. Gerçek operasyonel veriyle kullanmayın.
- OSP-60 sözleşme varsayımlarının backend sahibi tarafından onaylanması gerekir.
- Monoton saat ve biyometrik kilit yok (OSP-73).
- Uygulama simgesi yok (bir lint uyarısı). Release imzasız ve küçültülmemiş.
- Henüz hiçbir şey push edilmedi.
- Bir inceleme maddesi (R7, supabase-kt içinde olası bir yarış durumu) ne kanıtlanabildi ne çürütülebildi; temizlikten sonra otomatik yenilemenin tekrar durdurulmasıyla hafifletildi.

### 8. Derleme, yapılandırma ve test
Ayrıntılar için [README.md](../README.md). Kısaca:
1. JDK 21 ve Android SDK platform 37.0 kurun. Gradle kurmayın; `./gradlew` kullanın.
2. `local.properties` oluşturun (asla commit etmeyin; depo herkese açık): `sdk.dir`, `SUPABASE_URL`,
   `SUPABASE_ANON_KEY`, `API_BASE_URL` ve isteğe bağlı `CONNECT_FAKE_BACKEND`.
3. Kontrol: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug assembleDebugAndroidTest`.
4. Telefonda: `./gradlew installDebug`; cleartext testi için `./gradlew connectedDebugAndroidTest`.

Telefonda elle test adımları:
1. Temiz kurulum, kurtarıcı olarak giriş: görev ekranı 72 saat sonrasını gösterir, uyarı iki ekranda da görünür.
2. Coordinator veya administrator ile giriş: rol hatası görünür; yeniden açınca giriş ekranı hâlâ gelir.
3. Uçak modu, sonra Görevi Bitir: çıkış çalışır, giriş ekranı "çıkış yapıldı" der; saklanan dosyada yalnızca `device_id` kalır.
4. Cleartext: `API_BASE_URL=http://...` ve `CONNECT_FAKE_BACKEND=false` ayarlayıp giriş yapın; arayüz
   "Bağlantı yok" gösterir, `adb logcat -s CONNECT` çıktısında "CLEARTEXT communication ... not permitted" görünür.

Yerel `http://` sunucuları tasarım gereği çalışmaz; HTTPS tüneli veya debug sahte backend kullanın.

### 9. Commit listesi (eskiden yeniye)
Liste İngilizce bölümün 9. başlığındaki tabloyla aynıdır (18 commit, 264dde0 ile 78f13e6 arası; tüm
mesajlar `OSP-74: ` önekini taşır). Başlangıç noktası `8a486ef`'ten bu yana toplam değişiklik: 68 dosya,
yaklaşık 4.900 eklenen satır.
