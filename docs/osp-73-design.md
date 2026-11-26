# OSP-73 Tasarım Notu: OfflineGrantPolicy

Jira: OSP-73 (epic OSP-56 / E10). Bağımlılıklar: OSP-60 (grant veren backend), OSP-74 (login), OSP-75 (şifreli depo).

## 1. Amaç
- Kurban ve Röle modları kimlik doğrulamasız açılır (AC-01).
- Saha Kurtarıcı Modu; bu kuruluma ait (`device_id`), `role == "rescuer"` ve çevrimdışı pencere içinde
  (varsayılan 72 sa) bir Offline Mission Grant ister.
- 1 saatlik access_token dolsa da pencere boyunca çalışılır; refresh_token tek başına yetki vermez.
- Pencere dolarsa veri silinmez, Röle ve pasif tarama sürer; yalnızca PII detayı ve bulut senkronu
  "Çevrimiçi Yeniden Doğrulama Gerekli" ile kilitlenir.
- Kayıp telefona karşı Kurtarıcı Modu ve PII ekranları, yeniden başlatma veya 15 dakikadan uzun arka
  plan sonrası BiometricPrompt ister.

## 2. Bileşenler
| Bileşen | Dosya | Görev |
|---|---|---|
| `MonotonicClock` | `auth/grant/Clocks.kt` | `SystemClock.elapsedRealtime()` + `Settings.Global.BOOT_COUNT` |
| `OfflineGrantPolicy` | `auth/grant/OfflineGrantPolicy.kt` | Saf karar mantığı; hiçbir şey silmez. `evaluate()` durum, `access()` yetenek kararı |
| `GrantAnchor` | `auth/grant/GrantAnchor.kt` | Pencereden harcanan sürenin monoton saat kaydı |
| `DataStoreGrantAnchorStore` | `data/grant/DataStoreGrantAnchorStore.kt` | Çapa için ayrı şifreli dosya (`connect_grant_anchor.enc`, ayrı anahtar) |
| `ClockChangeReceiver` | `system/ClockChangeReceiver.kt` | Saat değişince çapayı monoton saatle günceller |
| `BootReceiver`, `BootWallFloor` | `system/BootReceiver.kt`, `system/BootWallFloor.kt` | Açılışta duvar saatini kaydeder, sonra grant kontrolü |
| `OfflineGrantChecker` | `auth/grant/OfflineGrantChecker.kt` | Policy + gerçek saatler + çapa deposu; ekranların tek giriş noktası |
| `AppLockState` | `auth/grant/AppLockState.kt` | Süreç başında kilitli; >15 dk arka plan sonrası yeniden kilit |
| `BiometricGate` | `ui/lock/BiometricGate.kt` | "Kilidi Aç" ekranı + BiometricPrompt |

Bağlantılar: `AppContainer` nesneleri kurar; `RescuerAuthViewModel` grant'i en az dakikada bir (ve kalan
süre bittiği anda) kontrol eder; `RescuerAuthRoute` kilitliyken `MissionScreen` yerine `BiometricGate`
gösterir; `LogoutUseCase` grant ile birlikte çapayı da siler.

## 3. Süre hesabı (saat geri almaya karşı)
- Aynı açılışta: `harcanan = çapa.harcanan + (elapsedRealtime − çapa.elapsed)`. Duvar saati geri alınsa da azalmaz.
- Yeniden başlatmada (BOOT_COUNT değişti veya elapsedRealtime geriledi):
  `harcanan = max(çapa.harcanan, şimdi − authenticated_at)`; kapalı geçen süre duvar saatiyle sayılır.
- Geri alma: `şimdi < authenticated_at − 5 dk` veya `şimdi < son_görülen − 5 dk` → süre dolmuş sayılır.
  Geri almada da çapa yazılır (harcanan süre korunur, son görülen zaman geri gitmez); yalnızca çapa hiç
  yokken yazılmaz.
- Saat değişikliği: `ClockChangeReceiver` (`ACTION_TIME_CHANGED`, uygulama kapalıyken de çalışır) aynı
  açılışta kontrol çalıştırır; monoton saatle harcanan süre yeniden başlatmadan önce çapaya yazılır.
  Eşzamanlı kontroller `OfflineGrantChecker` içinde `Mutex` ile sıraya girer.
- Açılış kaydı: `BootReceiver` açılışta duvar saatini `BootWallFloor`'a (cihaz korumalı depo) yazar.
  `harcanan ≥ max(şimdi, açılış_kaydı) − authenticated_at` ve `son_görülen ≥ açılış_kaydı`.
- Süre doldu: `şimdi ≥ expires_at` VEYA `harcanan ≥ pencere` VEYA geri alma.
- Çapa `grantKey` (user, device, authenticated_at, expires_at) ile tek grant'e bağlıdır. Çapa yoksa
  (ör. OSP-74 sürümünden kalan grant) duvar saatinden kurulur.
- `son_görülen` asla geriye gitmez; çapa her kontrolde yeniden yazılır.

## 4. Yetenek tablosu
| Yetenek | Grant gerekir | Süre dolunca | Biyometri |
|---|---|---|---|
| VICTIM_MODE, RELAY_MODE, PASSIVE_SCAN | Hayır | Açık | Hayır |
| RESCUER_MODE | Evet | Açık ("Çevrimiçi Yeniden Doğrulama Gerekli") | Evet |
| VICTIM_PII_DETAIL | Evet | Kilitli | Evet |
| CLOUD_SYNC | Evet | Kilitli | Hayır |

Grant başka cihaza veya başka role aitse (`Mismatch`) Kurtarıcı Modu reddedilir; ViewModel grant'i
yerel olarak siler ve `GrantInvalid` hatasıyla giriş ekranını gösterir. Kurban kayıtlarına dokunulmaz.

## 5. Biyometri
- İzinli doğrulayıcılar: API 30+ `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`; API 26–29
  `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` (STRONG|CREDENTIAL orada desteklenmez).
- Parmak izi / PIN uygulamaya gelmez; yalnızca Android'in "başarılı" sonucu gelir.
- `MainActivity` artık `FragmentActivity` (BiometricPrompt gereği).
- Kilit `ProcessLifecycleOwner` gözlemcisiyle ana iş parçacığında senkron güncellenir; kilit ilk kare
  çizilmeden devreye girer. Süre monoton saatle ölçülür.
- Ekran kilidi yoksa Kurtarıcı Modu açılmaz; "Güvenlik ayarlarını aç" butonu gösterilir.
- Biyometri geçici olarak kullanılamıyorsa "Yeniden dene" butonu durumu yeniden okur. Her durumda
  "Şifreyle çevrimiçi giriş yap" vardır: giriş ekranına gider, grant silinmez, kilit açılmaz; yalnızca
  başarılı giriş kilidi açar (başarısızsa yeniden başlatma kilit ekranına döner).
- Başarılı çevrimiçi giriş (Supabase şifresi + backend grant) kilidi açar; kullanıcıdan şifreden hemen sonra
  ayrıca PIN istenmez. Başarısız giriş kilidi açmaz.

## 6. Kararlar
1. `androidx.biometric` 1.4.0-rc01 (1.1.0 2021'den kalma). `androidx.fragment` 1.9.1 açıkça eklendi.
2. Çapa ayrı şifreli DataStore'da: OSP-75 seri hale getiricisi, KENDİ Keystore anahtarı
   (`connect_grant_anchor_key`) ve farklı associated data (`connect_grant_anchor.v1`). Oturum anahtarı
   kalıcı hatada silinip yeniden üretildiğinde çapa deposu takılmaz; çapa anahtarı bozulursa yalnızca o
   silinir, çapa duvar saatinden yeniden kurulur.
3. Yeniden başlatma sonrası duvar saati + "son görülen zaman" kontrolü.
4. Ekran kilidi yoksa Kurtarıcı Modu engellenir; Kurban/Röle etkilenmez.
5. Rol kuralı issue'daki gibi `role == "rescuer"`; koordinatör sahada kurtarıcı olacaksa ayrı hesap gerekir.
6. Başarılı çevrimiçi giriş kilidi açar (emülatör testinden sonra alınan UX kararı).

## 7. Entegrasyon sözleşmesi (sonraki görevler)
Kurban, Röle, PII detay ve senkron ekranları henüz yok. Yazıldıklarında:
```kotlin
val status = grantChecker.check(store.readGrant())
when (grantChecker.access(Capability.VICTIM_PII_DETAIL, status)) { /* Allowed / LoginRequired / Denied / ReauthRequired */ }
if (appLock.isGuarded(Capability.VICTIM_PII_DETAIL)) { /* önce BiometricGate */ }
```

## 8. Bilinen sınırlar
- Root'lu cihazda çapa dosyası silinirse harcanan süre duvar saatinden yeniden kurulur (duvar saati ve
  geri alma kontrolleri sürer).
- NTP saati 5 dakikadan fazla geri çekerse PII, gerçek zaman son görülen zamanı geçene kadar kilitli
  kalır (fail-closed).
- Çapa okunamazsa (Keystore geçici hatası) durum `STATE_UNAVAILABLE` sayılır, PII/senkron kilitlenir,
  bir sonraki kontrol (en geç 60 sn) yeniden dener.
- Monoton saat yeniden başlatmada sıfırlanır. Uygulama kapalıyken geçen süre üç yoldan korunur:
  (1) saat değişirse `ClockChangeReceiver` aynı açılışta çapayı monoton saatle günceller;
  (2) telefon açılırken, kullanıcı kilidi açıp saati değiştiremeden önce `BootReceiver`
  (`LOCKED_BOOT_COMPLETED`, directBootAware) duvar saatini `BootWallFloor`'a yazar; policy bunu son görülen
  zaman ve harcanan süre için alt sınır olarak kullanır; (3) `BOOT_COMPLETED`'da tam kontrol çalışır.
  Böylece "uygulama kapalı 70 sa -> saati geri al -> yeniden başlat" ve "70 sa -> yeniden başlat -> saati
  geri al" sıraları kapanır (`clockChangeCheckKeepsUptimeAcrossReboot`, `rebootThenRollbackIsCaughtByBootFloor`).
  Açık kalanlar:
  - Uygulama **"Zorla durdur"** yapılmışsa sistem bu yayınları göndermez (uygulama yeniden açılana kadar);
    "zorla durdur -> 70 sa -> yeniden başlat -> saati geri al" sırası görülemez
    (`knownLimitWithoutBootSignalUptimeIsLost`).
  - Telefon **kapalıyken** geçen süre: açılışta saat donanım saatinden gelir; donanım saati de
    değiştirilmişse görülemez.
  Çevrimdışı güvenilir zaman kaynağı yoktur. İleride: GNSS zamanı alt sınır olarak.
- Açılış kaydı yalnızca artar; telefon açılırken saat ileri bir tarihteyse gerçek zaman o ana gelene kadar
  PII/senkron kilitli kalır (fail-closed). Root'lu cihazda kayıt dosyası değiştirilebilir (çapa gibi).
- Saat ileri alınıp süre dolarsa (`WALL_CLOCK` / `MONOTONIC_WINDOW`) saat düzeltilse de pencere geri
  açılmaz; harcanan süre çapaya yazılmıştır. Yeniden çevrimiçi giriş gerekir (fail-closed, bilinçli).
