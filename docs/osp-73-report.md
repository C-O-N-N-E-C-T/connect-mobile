# OSP-73 Work Report / Çalışma Raporu

Jira story OSP-73 (CONNECT project, epic OSP-56 / E10): Android OfflineGrantPolicy, configurable 72 h
window, expiry preservation and Biometric/PIN lock. Repository: `connect-mobile`, branch `OSP-73`.
Date: 2026-11-25, updated 2026-11-26 (review fixes, OSP-76 merge). Design: [osp-73-design.md](osp-73-design.md).

[English](#english) · [Türkçe](#türkçe)

---

## English

### 1. Summary
OSP-73 adds the offline access rules on Android. Victim and Relay modes never need a login. Field
Rescuer Mode needs the offline mission grant from OSP-60 that belongs to this install, has role
`rescuer` and is inside the configurable offline window (default 72 h). The window is checked with the
wall clock and with the monotonic clock, so moving the phone clock back does not extend it. When the
window ends, no data is deleted: only victim PII details and cloud sync are locked behind "Online
Re-Authentication Required". After an app restart or more than 15 minutes in the background, Field
Rescuer Mode asks for a fingerprint or the device PIN/pattern (BiometricPrompt). A successful online
login unlocks it directly (no PIN right after the password).

**Status:** code complete and pushed (PR #4); build, all 215 unit tests (with main/OSP-76) and lint
pass; tested on an Android 17 emulator (sections 5 and 6). Not yet on a physical phone.

### 2. Acceptance criteria
| Requirement | Implementation | Verification |
|---|---|---|
| Victim and Relay modes open with no grant (AC-01) | `Capability.requiresGrant = false` | `OfflineGrantPolicyTest.victimRelayAndScanNeedNoGrant`, `AppLockStateTest.onlyRescuerAndPiiAreGuarded` |
| Grant must match `device_id` and role `rescuer` | `GrantStatus.Mismatch` → `Access.Denied`; ViewModel wipes it locally | `grantForAnotherDeviceIsDenied`, `nonRescuerRoleIsDenied`, `RescuerAuthViewModelTest.grantForAnotherDeviceIsWipedToLogin` |
| Configurable window `DEFAULT_OFFLINE_GRANT_HOURS = 72` | `OfflineGrantPolicy(offlineGrantHours)` | `configurableWindow`, `validJustBeforeWindowExpiredAtWindow` |
| Checked against `expires_at` and `elapsedRealtime()` (clock rollback) | `GrantAnchor` with consumed time, boot count and last-seen wall time | `clockRollbackDoesNotExtendWindow`, `rebootCountsOffTimeByWallClock`, `rebootWithRolledBackClockExpires`, `clockBeforeAuthenticatedAtIsRollback` |
| Works after the 1 h access token expires; refresh_token alone grants nothing | The policy has no token input; only the grant decides | `rescuerModeWorksAfterAccessTokenExpiry`, `RescuerAuthViewModelTest.missionStaysOpenAfterAccessTokenHour` |
| On expiry keep 100 % of records, keep relay/scan, lock only PII and sync | `lockedOnExpiry` only for `VICTIM_PII_DETAIL` and `CLOUD_SYNC`; `MissionScreen` message | `expiryLocksOnlyPiiAndSync`, ViewModel expiry tests (no store events) |
| BiometricPrompt after restart or >15 min background | `AppLockState`, `BiometricGate`, `MainActivity` is a `FragmentActivity` | `AppLockStateTest` (9 tests), `AuthenticatorsTest`; prompt emulator-tested (section 5) |

PII detail, cloud sync, Victim and Relay screens do not exist yet. The policy already decides for them
and the contract for those stories is in the design note, section 7.

### 3. Changed files
New: `auth/grant/{Clocks, OfflineGrantPolicy, GrantAnchor, OfflineGrantChecker, AppLockState}.kt`,
`data/grant/DataStoreGrantAnchorStore.kt`, `ui/lock/{Authenticators, BiometricGate}.kt`, five test files.
Updated: `libs.versions.toml`, `app/build.gradle.kts` (biometric 1.4.0-rc01, fragment 1.9.1),
`MainActivity`, `AppContainer`, `LogoutUseCase` (also clears the anchor), `SessionBootstrap` (comment),
`RescuerAuthViewModel`, `RescuerAuthRoute`, `MissionScreen`, `strings.xml` (EN/TR), `LogoutUseCaseTest`,
`RescuerAuthViewModelTest`. Not changed: `LoginUseCase`, `data/session/*`.

### 4. Verification
- `./gradlew testDebugUnitTest assembleDebug lintDebug`: **215 unit tests, 0 failures** (including main /
  OSP-76); debug APK built with Build Tools 37.0.0, no override.
- Lint: no findings in OSP-73 code (older warnings: dependency updates, app icon, OSP-76 plurals).

### 5. Emulator test (Medium_Phone_API_37.0, debug build, real Supabase sign-in with the seed rescuer, fake OSP-60 backend)
| Scenario | Result |
|---|---|
| No screen lock on the device | Pass: "Set up a screen lock" message and security settings button |
| Screen lock set, back to the app | Pass: prompt opens by itself |
| App restart (process killed) | Pass: lock + prompt |
| Prompt cancelled / wrong PIN / right PIN | Pass: stays locked / stays locked / unlocks |
| 10 s in background | Pass: no lock |
| 16 min in background | Pass: lock + prompt |
| Fresh login (S2) | Pass: "Field Rescuer Mode active", expiry exactly 72 h later |
| Successful login in a locked process | Pass: no extra PIN prompt; a restart after it locks again |
| Device clock moved back 2 days (S5) | Pass: window not extended; screen switches to "Online Re-Authentication Required" within 25 s, back to active when the clock is fixed |
| Device clock moved forward 73 h (S4) | Pass: "Online Re-Authentication Required"; session/grant file untouched |
| End Mission / Log Out | Pass: login screen; no lock on the next start |

No crash (`FATAL EXCEPTION`) during the whole run. The device clock was restored and automatic time re-enabled afterwards.

### 6. Review fixes (2026-11-26)
| Finding | Fix | Verification |
|---|---|---|
| Window extended by "app closed 70 h -> roll clock back -> reboot" | `ClockChangeReceiver` (ACTION_TIME_CHANGED) updates the anchor with the monotonic clock; rollback also records consumed time | `clockChangeCheckKeepsUptimeAcrossReboot`, `deepRollbackStillRecordsConsumedTime`; emulator: receiver starts the killed app, rollback -> re-auth, fixed clock -> active |
| Same, but "reboot first, then roll back" | `BootReceiver` (directBootAware) stores `(BOOT_COUNT, wall clock)` at LOCKED_BOOT_COMPLETED; the policy uses it only for the current boot after a reboot | `rebootThenRollbackIsCaughtByBootFloor`, `futureBootRecordThenNewLoginIsValid`, `bootRecordFromPreviousBootIsIgnored`; emulator: record written while locked, rollback after reboot -> re-auth |
| No way out of the lock screen | "Try again" and "Sign in online with password" (grant kept, lock kept until a successful login) | `signInFromLockKeepsGrantAndLock`; emulator: button shown, login screen opens, restart returns to the lock |
| Anchor shared the session key | Own Keystore alias `connect_grant_anchor_key` | emulator: anchor rebuilt after the key change, mission active |
| OSP-76 merge | Upload gate runs only with a Valid grant (CLOUD_SYNC locked on expiry) | `expiredGrantNeverSyncs`, `syncStopsWhenWindowEnds` |

### 7. Open items
- Team decision: `androidx.biometric` 1.4.0-rc01 or stable 1.1.0.
- Known limit: a force-stopped app gets no broadcasts (design note section 8).
- Real OSP-60 backend: tested only with the debug fake backend (CONNECT_FAKE_BACKEND=true).
- Physical phone and API 26-29 not tested yet.

---

## Türkçe

### 1. Özet
OSP-73 Android'e çevrimdışı erişim kurallarını ekler. Kurban ve Röle modları hiçbir zaman giriş
istemez. Saha Kurtarıcı Modu; OSP-60'tan gelen, bu kuruluma ait, rolü `rescuer` olan ve ayarlanabilir
çevrimdışı pencere (varsayılan 72 sa) içindeki görev yetkisini (grant) ister. Pencere hem duvar saati
hem monoton saatle kontrol edilir; telefon saatini geri almak süreyi uzatmaz. Pencere dolduğunda hiçbir
veri silinmez: yalnızca kazazede kişisel verileri (PII) ve bulut senkronu "Çevrimiçi Yeniden Doğrulama
Gerekli" uyarısıyla kilitlenir. Uygulama yeniden başlatıldığında veya 15 dakikadan uzun arka planda
kaldığında Kurtarıcı Modu parmak izi ya da cihaz PIN/deseni ister (BiometricPrompt). Başarılı çevrimiçi giriş
kilidi doğrudan açar (şifreden hemen sonra ayrıca PIN sorulmaz).

**Durum:** kod tamam ve push edildi (PR #4); derleme, 215 birim testinin tamamı (main/OSP-76 ile) ve lint
geçiyor; Android 17 emülatöründe test edildi (bölüm 4 ve 5). Fiziksel telefonda henüz denenmedi.

### 2. Kabul kriterleri
| İstek | Uygulama | Doğrulama |
|---|---|---|
| Kurban ve Röle grant'siz açılır (AC-01) | `Capability.requiresGrant = false` | `victimRelayAndScanNeedNoGrant`, `onlyRescuerAndPiiAreGuarded` |
| Grant `device_id` ve `rescuer` rolüyle eşleşmeli | `Mismatch` → `Denied`; ViewModel yerel olarak siler | `grantForAnotherDeviceIsDenied`, `nonRescuerRoleIsDenied`, `grantForAnotherDeviceIsWipedToLogin` |
| Ayarlanabilir pencere (72 sa) | `OfflineGrantPolicy(offlineGrantHours)` | `configurableWindow`, `validJustBeforeWindowExpiredAtWindow` |
| `expires_at` + `elapsedRealtime()` (saat geri alma) | `GrantAnchor`: harcanan süre, açılış sayacı, son görülen zaman | `clockRollbackDoesNotExtendWindow`, `rebootCountsOffTimeByWallClock`, `rebootWithRolledBackClockExpires` |
| 1 saatlik token dolunca da çalışır; refresh_token tek başına yetki vermez | Policy token'a bakmaz | `rescuerModeWorksAfterAccessTokenExpiry`, `missionStaysOpenAfterAccessTokenHour` |
| Süre dolunca kayıtlar %100 korunur, röle/tarama sürer, yalnızca PII ve senkron kilitlenir | `lockedOnExpiry` yalnızca PII ve SYNC'te | `expiryLocksOnlyPiiAndSync`, ViewModel testleri |
| Yeniden başlatma / >15 dk arka plan sonrası BiometricPrompt | `AppLockState`, `BiometricGate`, `FragmentActivity` | `AppLockStateTest`, `AuthenticatorsTest`; prompt emülatörde test edildi (bölüm 4) |

PII, senkron, Kurban ve Röle ekranları henüz yok; policy onlar için de karar veriyor, sözleşme tasarım
notunun 7. bölümünde.

### 3. Doğrulama
- `./gradlew testDebugUnitTest assembleDebug lintDebug`: **215 birim testi, 0 hata** (main / OSP-76 dahil);
  debug APK Build Tools 37.0.0 ile, override olmadan derlendi.
- Lint: OSP-73 kodunda bulgu yok (eski uyarılar: kütüphane güncellemeleri, uygulama ikonu, OSP-76 plurals).

### 4. Emülatör testi (Medium_Phone_API_37.0, debug build, seed rescuer hesabıyla gerçek Supabase girişi, sahte OSP-60 backend)
| Senaryo | Sonuç |
|---|---|
| Cihazda ekran kilidi yok | Geçti: "Ekran kilidi ayarlayın" mesajı ve güvenlik ayarları butonu |
| Ekran kilidi kurulup uygulamaya dönülünce | Geçti: prompt kendiliğinden açılır |
| Uygulama yeniden başlatma | Geçti: kilit + prompt |
| Prompt iptal / yanlış PIN / doğru PIN | Geçti: kilitli kalır / kilitli kalır / açılır |
| 10 sn arka plan | Geçti: kilit yok |
| 16 dk arka plan | Geçti: kilit + prompt |
| Yeni giriş (S2) | Geçti: "Field Rescuer Mode active", bitiş tam 72 sa sonra |
| Kilitli süreçte başarılı giriş | Geçti: ayrıca PIN sorulmaz; sonraki yeniden başlatmada kilit yine gelir |
| Cihaz saati 2 gün geri (S5) | Geçti: süre uzamaz; 25 sn içinde "Çevrimiçi Yeniden Doğrulama Gerekli", saat düzelince yeniden aktif |
| Cihaz saati 73 sa ileri (S4) | Geçti: "Çevrimiçi Yeniden Doğrulama Gerekli"; oturum/grant dosyasına dokunulmadı |
| Görevi Bitir / Çıkış | Geçti: giriş ekranı; sonraki açılışta kilit yok |

Test boyunca hiç çökme (`FATAL EXCEPTION`) olmadı. Sonrasında cihaz saati geri alındı, otomatik saat açıldı.

### 5. Review düzeltmeleri (2026-11-26)
| Bulgu | Düzeltme | Doğrulama |
|---|---|---|
| "Uygulama kapalı 70 sa -> saati geri al -> yeniden başlat" ile süre uzatma | `ClockChangeReceiver` (ACTION_TIME_CHANGED) çapayı monoton saatle günceller; geri almada da harcanan süre kaydedilir | `clockChangeCheckKeepsUptimeAcrossReboot`, `deepRollbackStillRecordsConsumedTime`; emülatör: receiver kapalı uygulamayı başlatır, geri alma -> yeniden doğrulama, saat düzelince aktif |
| Aynısı, "önce yeniden başlat, sonra geri al" | `BootReceiver` (directBootAware) LOCKED_BOOT_COMPLETED'da `(BOOT_COUNT, duvar saati)` kaydeder; policy yalnızca bu açılışta ve yeniden başlatma sonrası kullanır | `rebootThenRollbackIsCaughtByBootFloor`, `futureBootRecordThenNewLoginIsValid`, `bootRecordFromPreviousBootIsIgnored`; emülatör: kayıt kilitliyken yazıldı, geri alma -> yeniden doğrulama |
| Kilit ekranından çıkış yok | "Yeniden dene" ve "Şifreyle çevrimiçi giriş yap" (grant ve kilit korunur, yalnızca başarılı giriş açar) | `signInFromLockKeepsGrantAndLock`; emülatör: buton görünür, giriş ekranı açılır, yeniden başlatma kilide döner |
| Çapa oturum anahtarını paylaşıyordu | Ayrı Keystore anahtarı `connect_grant_anchor_key` | emülatör: anahtar değişince çapa yeniden kuruldu, görev aktif |
| OSP-76 merge | Yükleme kapısı yalnızca grant Valid iken çalışır (CLOUD_SYNC süre dolunca kilitli) | `expiredGrantNeverSyncs`, `syncStopsWhenWindowEnds` |

### 6. Açık işler
- Ekip kararı: `androidx.biometric` 1.4.0-rc01 mi, kararlı 1.1.0 mı.
- Bilinen sınır: "Zorla durdur" yapılmış uygulamaya yayın gelmez (tasarım notu §8).
- Gerçek OSP-60 backend: yalnızca debug sahte backend ile test edildi (CONNECT_FAKE_BACKEND=true).
- Fiziksel telefon ve API 26-29 henüz denenmedi.
