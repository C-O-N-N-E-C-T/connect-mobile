# connect-mobile

Android app of **CONNECT**, an offline-first search-and-rescue system for earthquakes
(Kotlin + Jetpack Compose).

[English](#english) · [Türkçe](#türkçe)

---

## English

### What the app does so far (Jira OSP-74)
Only the **Field Rescuer pre-deployment login** exists so far.

1. The rescuer signs in with e-mail and password through Supabase Auth (supabase-kt).
2. The app calls `POST /api/v1/auth/session` on connect-backend with a random **install device ID**.
   This ID is a UUID created on first launch; it is never the IMEI or ANDROID_ID.
3. The app accepts only role `rescuer`. A `coordinator` or `administrator` sees a role error and is
   signed out at once.
4. The app stores the session and the **72-hour offline mission grant**. It then shows
   *"Authenticated for offline deployment until &lt;time&gt;"* and the supplementary-channel
   disclaimer (NFR-SAFE-03).
5. **End Mission / Log Out** deletes the local grant and tokens only. Unsynced victim records and the
   device ID are kept.

All traffic is **HTTPS only**: the Network Security Config sets `cleartextTrafficPermitted="false"`
with no exceptions, also in debug builds.

Not built yet (other stories):
- encrypted storage (OSP-75; the current store is a temporary plaintext DataStore);
- grant expiry enforcement and the biometric lock (OSP-73);
- reconnection (OSP-76);
- the backend endpoints themselves (OSP-60).

### Requirements
- **JDK 21.** Android Studio's bundled JDK is fine.
- **Android SDK** with platform **API 37** (`platforms;android-37.0`) and build-tools. Android Studio
  usually downloads a missing platform on first sync.
- **Do not install Gradle or Kotlin.** Always use the wrapper `./gradlew` (Gradle 9.7.1; the
  download is checked with its SHA-256). Kotlin comes from the Gradle plugins in
  `gradle/libs.versions.toml`. Java toolchain: 21.

### Setup: `local.properties`
Create `local.properties` in the repo root. Android Studio creates it with `sdk.dir`.

> **Never commit `local.properties`.** The repo is PUBLIC, and the file is already in `.gitignore`.

```properties
sdk.dir=/path/to/Android/Sdk
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_ANON_KEY=<anon public key from the Supabase dashboard>
API_BASE_URL=https://<connect-backend host>
# Debug builds only. Default true = fake OSP-60 backend; set false to call API_BASE_URL.
CONNECT_FAKE_BACKEND=true
```
- **Missing keys:** the app still builds, but login shows *"This build is not configured for online
  login."*
- **Fake backend on:** `API_BASE_URL` is not needed; `SUPABASE_URL` and `SUPABASE_ANON_KEY` are.
- **Secrets:** never put keys or tokens in code, logs or tests. The app never logs tokens.

### Build and test
```sh
./gradlew assembleDebug                 # debug APK -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest             # JVM unit tests
./gradlew lintDebug                     # Android lint
./gradlew assembleRelease               # release build (unsigned, compile check only)
./gradlew connectedDebugAndroidTest     # instrumented tests on a USB phone/emulator (cleartext proof)
# full check used for every commit:
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug assembleDebugAndroidTest
```
Install on a phone with USB debugging: `./gradlew installDebug`.

### Supabase prerequisite (needed to log in)
- The hosted Supabase project must have the connect-backend migrations applied (OSP-58 auth schema
  and the custom access token hook, see `connect-backend/docs/supabase-settings.md`).
- Each test user needs an active `public.user_profiles` row with a role.
- Without them the token has no `user_role` claim, and the debug fake backend answers
  *"This account is disabled"* for every user, including rescuers.

### Developing with the debug fake backend (HTTPS only)
- **OSP-60 is not available yet.** Debug builds therefore use `FakeAuthBackend`:
  - Sign-in itself is **real**. It goes to your hosted Supabase project, so you need a real user.
  - The fake then reads the `user_role` claim of the token: `rescuer` gets a 72 h grant, other roles
    get no grant, and `null` gets "account disabled".
  - The fake exists only in `src/debug`. It is **not** in the release APK.
- **No local http servers.** Because cleartext is blocked, `http://` endpoints such as a local
  backend or the local Supabase CLI (`http://127.0.0.1:54321`) do not work. To use a local backend,
  expose it through an HTTPS tunnel (for example `cloudflared tunnel --url http://localhost:3000`)
  and set `API_BASE_URL` to the `https://` URL with `CONNECT_FAKE_BACKEND=false`.
- **Seeing the block:** set `API_BASE_URL=http://…` with `CONNECT_FAKE_BACKEND=false` and try to log
  in. The UI shows the *No connection* error, and `adb logcat -s CONNECT` shows
  `CLEARTEXT communication … not permitted by network security policy`. Only debug builds log this.

### Project structure
```
app/src/main/java/com/connect/mobile/
  ConnectApplication.kt, AppContainer.kt   manual dependency injection (no DI framework)
  MainActivity.kt                          single activity, Compose
  config/AppConfig.kt                      local.properties -> BuildConfig -> config
  data/session/                            SessionStore (temporary DataStore, TODO OSP-75) + supabase-kt session adapter
  data/device/DeviceIdProvider.kt          install UUID + device name
  data/network/HttpClientFactory.kt        the only HTTP stack (Ktor + OkHttp engine)
  data/backend/                            OSP-60 client: DTOs (wire names), KtorAuthBackend
  data/supabase/                           Supabase Auth gateway (sign-in, sign-out, local clear)
  auth/                                    LoginUseCase, LogoutUseCase, SessionBootstrap, AuthError
  ui/                                      RescuerAuthViewModel (state machine), login + mission screens,
                                           common/SupplementaryDisclaimer (reusable by all modes)
app/src/debug/     FakeAuthBackend + debug AuthBackendFactory     (not in release)
app/src/release/   release AuthBackendFactory (always the real backend)
app/src/test/      JVM unit tests        app/src/testDebug/  fake backend tests
app/src/androidTest/CleartextPolicyTest.kt   OS-level HTTPS-only proof
app/src/main/res/xml/network_security_config.xml   cleartextTrafficPermitted="false"
```

### Documents
- [docs/osp-74-design.md](docs/osp-74-design.md): design of this story (versions, contracts, state
  machine, test plan).
- [docs/osp-60-contract-assumptions.md](docs/osp-60-contract-assumptions.md): what the app assumes
  about the OSP-60 backend endpoints (handoff note for the backend developer).

---

## Türkçe

### Uygulamanın şu anki kapsamı (Jira OSP-74)
Şimdilik yalnızca **Saha Kurtarıcısı göreve çıkış öncesi girişi** var.

1. Kurtarıcı, Supabase Auth (supabase-kt) üzerinden e-posta ve parolayla giriş yapar.
2. Uygulama, rastgele bir **kurulum cihaz kimliğiyle** connect-backend'deki
   `POST /api/v1/auth/session` uç noktasını çağırır. Bu kimlik ilk açılışta üretilen bir UUID'dir;
   hiçbir zaman IMEI veya ANDROID_ID değildir.
3. Yalnızca `rescuer` rolü kabul edilir. `coordinator` veya `administrator` rol hatası görür ve
   hemen çıkış yaptırılır.
4. Uygulama oturumu ve **72 saatlik çevrimdışı görev yetkisini** (grant) saklar. Ardından
   *"Çevrimdışı görev için &lt;zaman&gt; tarihine kadar yetkilendirildi"* metnini ve yardımcı kanal
   uyarısını (NFR-SAFE-03) gösterir.
5. **Görevi Bitir / Çıkış Yap** yalnızca yerel grant'i ve token'ları siler. Senkronize edilmemiş
   kazazede kayıtları ve cihaz kimliği korunur.

Tüm trafik **yalnızca HTTPS** üzerinden gider: Network Security Config
`cleartextTrafficPermitted="false"` olarak ayarlıdır, hiçbir istisna yoktur; debug derlemede de
böyledir.

Henüz yapılmayanlar (başka hikâyeler):
- şifreli depolama (OSP-75; şu anki depo geçici, düz metin bir DataStore'dur);
- grant süresinin uygulanması ve biyometrik kilit (OSP-73);
- yeniden bağlanma (OSP-76);
- backend uç noktalarının kendisi (OSP-60).

### Gereksinimler
- **JDK 21.** Android Studio'nun kendi JDK'sı yeterlidir.
- **API 37** platformu (`platforms;android-37.0`) ve build-tools içeren **Android SDK**. Eksik
  platformu Android Studio genellikle ilk senkronizasyonda kendisi indirir.
- **Gradle veya Kotlin kurmayın.** Her zaman wrapper'ı (`./gradlew`) kullanın (Gradle 9.7.1;
  indirilen dosya SHA-256 ile doğrulanır). Kotlin, `gradle/libs.versions.toml` içindeki Gradle
  eklentilerinden gelir. Java toolchain: 21.

### Kurulum: `local.properties`
Depo kökünde `local.properties` oluşturun. Android Studio dosyayı `sdk.dir` ile oluşturur.

> **`local.properties` dosyasını asla commit etmeyin.** Depo HERKESE AÇIKTIR; dosya zaten
> `.gitignore` içindedir.

```properties
sdk.dir=/Android/Sdk/yolu
SUPABASE_URL=https://<proje-ref>.supabase.co
SUPABASE_ANON_KEY=<Supabase panelindeki anon public anahtar>
API_BASE_URL=https://<connect-backend sunucusu>
# Yalnızca debug. Varsayılan true = sahte OSP-60 backend'i; false yapılırsa API_BASE_URL çağrılır.
CONNECT_FAKE_BACKEND=true
```
- **Eksik anahtar:** uygulama yine derlenir, ancak giriş ekranı *"Bu sürüm çevrimiçi giriş için
  yapılandırılmamış."* mesajını gösterir.
- **Sahte backend açıkken:** `API_BASE_URL` gerekmez; `SUPABASE_URL` ve `SUPABASE_ANON_KEY`
  gereklidir.
- **Gizli bilgiler:** anahtar veya token'ları asla koda, loglara ya da testlere yazmayın. Uygulama
  token'ları hiçbir zaman loglamaz.

### Derleme ve test
```sh
./gradlew assembleDebug                 # debug APK -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest             # JVM birim testleri
./gradlew lintDebug                     # Android lint
./gradlew assembleRelease               # release derlemesi (imzasız, yalnızca derleme kontrolü)
./gradlew connectedDebugAndroidTest     # USB'deki telefonda/emülatörde cihaz testleri (cleartext kanıtı)
# her commit öncesi tam kontrol:
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug assembleDebugAndroidTest
```
USB hata ayıklaması açık bir telefona kurmak için: `./gradlew installDebug`.

### Supabase ön koşulu (giriş için gerekli)
- Barındırılan Supabase projesine connect-backend migration'ları uygulanmış olmalıdır (OSP-58 auth
  şeması ve custom access token hook; bkz. `connect-backend/docs/supabase-settings.md`).
- Her test kullanıcısının rolü olan, aktif bir `public.user_profiles` satırı olmalıdır.
- Bunlar yoksa token'da `user_role` claim'i olmaz ve debug sahte backend'i, kurtarıcılar dahil her
  kullanıcıya *"Bu hesap devre dışı"* yanıtı verir.

### Debug sahte backend ile geliştirme (yalnızca HTTPS)
- **OSP-60 henüz yok.** Bu yüzden debug derlemeleri `FakeAuthBackend` kullanır:
  - Girişin kendisi **gerçektir**. Barındırılan Supabase projenize gider, bu yüzden gerçek bir
    kullanıcı gerekir.
  - Sahte backend ardından token'daki `user_role` claim'ini okur: `rescuer` 72 saatlik grant alır,
    diğer roller grant almaz, `null` ise "hesap devre dışı" alır.
  - Sahte backend yalnızca `src/debug` içindedir; **release APK'da bulunmaz**.
- **Yerel http sunucusu kullanılamaz.** Cleartext engellendiği için `http://` uç noktaları
  (örneğin yerel backend veya yerel Supabase CLI, `http://127.0.0.1:54321`) çalışmaz. Yerel backend
  kullanmak için onu bir HTTPS tüneliyle dışarı açın (örneğin
  `cloudflared tunnel --url http://localhost:3000`). Ardından `API_BASE_URL`'i `https://` adresine
  ayarlayın ve `CONNECT_FAKE_BACKEND=false` yapın.
- **Engeli görmek:** `API_BASE_URL=http://…` ve `CONNECT_FAKE_BACKEND=false` ayarlayıp giriş yapmayı
  deneyin. Arayüz *Bağlantı yok* hatasını gösterir; `adb logcat -s CONNECT` çıktısında
  `CLEARTEXT communication … not permitted by network security policy` görünür. Bunu yalnızca debug
  derlemeleri loglar.

### Proje yapısı
```
app/src/main/java/com/connect/mobile/
  ConnectApplication.kt, AppContainer.kt   elle bağımlılık enjeksiyonu (DI framework yok)
  MainActivity.kt                          tek activity, Compose
  config/AppConfig.kt                      local.properties -> BuildConfig -> yapılandırma
  data/session/                            SessionStore (geçici DataStore, TODO OSP-75) + supabase-kt oturum adaptörü
  data/device/DeviceIdProvider.kt          kurulum UUID'si + cihaz adı
  data/network/HttpClientFactory.kt        tek HTTP yığını (Ktor + OkHttp motoru)
  data/backend/                            OSP-60 istemcisi: DTO'lar (alan adları), KtorAuthBackend
  data/supabase/                           Supabase Auth geçidi (giriş, çıkış, yerel temizlik)
  auth/                                    LoginUseCase, LogoutUseCase, SessionBootstrap, AuthError
  ui/                                      RescuerAuthViewModel (durum makinesi), giriş + görev ekranları,
                                           common/SupplementaryDisclaimer (tüm modlarda kullanılabilir)
app/src/debug/     FakeAuthBackend + debug AuthBackendFactory     (release'de yok)
app/src/release/   release AuthBackendFactory (her zaman gerçek backend)
app/src/test/      JVM birim testleri    app/src/testDebug/  sahte backend testleri
app/src/androidTest/CleartextPolicyTest.kt   cihaz üzerinde HTTPS-only kanıtı
app/src/main/res/xml/network_security_config.xml   cleartextTrafficPermitted="false"
```

### Belgeler
- [docs/osp-74-design.md](docs/osp-74-design.md): bu hikâyenin tasarımı (sürümler, sözleşmeler, durum
  makinesi, test planı).
- [docs/osp-60-contract-assumptions.md](docs/osp-60-contract-assumptions.md): uygulamanın OSP-60
  backend uç noktaları hakkındaki varsayımları (backend geliştiricisine devir notu).
