# OSP-60 contract assumptions made by the Android app (OSP-74)

[English](#english) · [Türkçe](#türkçe)

---

## English

**For:** the backend developer of Jira OSP-60 (`POST /api/v1/auth/session`, `POST /api/v1/auth/logout`).
**Why:** OSP-74 (Android Field Rescuer login) was built before OSP-60 exists. The app assumes the
contract below. If your implementation differs, tell the Android lead. Every assumption is also marked
in the code; to list them, run:

```sh
grep -rn "OSP-60 ASSUMPTION" app/src
```

Until OSP-60 is deployed, debug builds use a fake backend (`FakeAuthBackend`, debug only) that
follows this contract.

### A1. `POST /api/v1/auth/session`: request
The app sends this request right after a successful Supabase e-mail/password sign-in:

```http
POST /api/v1/auth/session
Authorization: Bearer <supabase access_token>
Content-Type: application/json

{"device_id":"3f1c2b9e-7a4d-4e8b-9c1a-2d5e6f708192","device_name":"Samsung SM-A546B"}
```
- `device_id` is a random v4 UUID created on the first launch of the app. It does not change on
  logout. It changes only on reinstall, or if the app's local session file is corrupt (it is then
  regenerated). It is never the IMEI or ANDROID_ID.
- `device_name` is `"<manufacturer> <model>"`, at most 100 characters.

### A2. `POST /api/v1/auth/session`: 200 response
For a **rescuer**:
```json
{
  "user_id": "8d0f6a52-1c3e-4b7a-9f21-5e6d7c8b9a01",
  "role": "rescuer",
  "device_id": "3f1c2b9e-7a4d-4e8b-9c1a-2d5e6f708192",
  "offline_mission_grant": {
    "user_id": "8d0f6a52-1c3e-4b7a-9f21-5e6d7c8b9a01",
    "device_id": "3f1c2b9e-7a4d-4e8b-9c1a-2d5e6f708192",
    "authenticated_at": "2026-11-19T10:00:00Z",
    "expires_at": "2026-11-22T10:00:00Z"
  }
}
```
For a **coordinator or administrator** (if you answer 200), the grant is absent (or `null`):
```json
{"user_id":"…","role":"coordinator","device_id":"3f1c2b9e-7a4d-4e8b-9c1a-2d5e6f708192"}
```
- **Grant key:** `offline_mission_grant`.
- **Role values:** `role` is the lowercase `public.user_profiles` value: `rescuer`, `coordinator`
  or `administrator`. The app decides **only** on this field, never on the JWT `user_role` claim.
- **Timestamps:** `authenticated_at` and `expires_at` are ISO-8601 **with an offset**, for example
  `2026-11-19T10:00:00Z` or `2026-11-19T10:00:00+00:00`. A value without an offset is rejected.
- **Unknown fields:** extra fields are ignored. The fields above are required, except the grant for
  non-rescuers.
- **Client sanity check:** the app rejects the grant ("invalid deployment grant", then a full
  logout) unless all of these hold:
  - `user_id ==` the Supabase user id (JWT `sub`) of the access token the app sent, and
    `device_id ==` the `device_id` the app sent (checked for every 200, also for non-rescuers)
  - `grant.user_id == user_id`
  - `grant.device_id ==` the `device_id` the app sent
  - `0 < expires_at − authenticated_at ≤ 72 h` (plus 60 s tolerance)
  - `expires_at` is later than the device's current time, and `authenticated_at` is at most 5 min
    after it (so server and device clocks must agree within 5 min)

### A3. Coordinator / administrator
The app accepts **either** of these, and both have the same effect: the role error is shown, then
logout runs.
- 200 with `role` ≠ `rescuer` and no grant;
- 403 `ROLE_FORBIDDEN`.

### A4. Errors
Errors use the unified error contract `{ "error_code": "...", "message": "..." }` (from
`connect-backend/src/types/errors.ts`). For example:
```json
{"error_code":"DEVICE_REVOKED","message":"Device has been revoked"}
```
The `message` is only logged in debug builds and is never shown to the user. The app shows
localised text based on `error_code`:

| HTTP | `error_code` | App reaction |
|---|---|---|
| 401 | `UNAUTHENTICATED` | "session could not be verified" |
| 403 | `ROLE_FORBIDDEN` | role error (see A3) |
| 403 | `ACCOUNT_DISABLED` | "account disabled". Assumed for a missing or inactive profile (`user_role` = null) |
| 403 | `DEVICE_REVOKED` | "device revoked" |
| 403 | `SESSION_NOT_BOUND` | generic server error |
| 429 | `RATE_LIMITED` (any 429) | "too many attempts" |
| 400 | `VALIDATION_ERROR` | "request rejected, update the app" |
| 404 / 5xx | `NOT_FOUND` / `INTERNAL_ERROR` / unknown / non-JSON body | generic server error |
| 2xx | unparsable body | "invalid deployment grant" |

Every error after the Supabase sign-in triggers a full logout (A5).

### A5. `POST /api/v1/auth/logout`
```http
POST /api/v1/auth/logout
Authorization: Bearer <supabase access_token>
```
- **No body.** Any 2xx means success. The response body is ignored.
- **Best effort:** the call has a 5 s timeout and errors are ignored. The local wipe always happens.
  The access token **may already be expired**.
- **When it is called:**
  - on "End Mission / Log Out";
  - **also right after a rejected login** (role rejection, any backend error, invalid grant), because
    `/auth/session` may already have bound the session. So a rejected coordinator produces a LOGOUT
    audit entry. Tell us if that is a problem.
- **Order:** the app deletes the local grant first, then calls `/auth/logout` and Supabase `/logout`
  (scope `local`) with the access token it holds in memory, and finally deletes the local tokens.

### Where each assumption lives in the Android code

| # | File (`app/src/...`) | Symbol |
|---|---|---|
| A1 | `main/.../data/backend/AuthDtos.kt` | `SessionRequest` |
| A1 | `main/.../data/backend/KtorAuthBackend.kt` | `bindSession`, `SESSION_PATH` |
| A2 | `main/.../data/backend/AuthDtos.kt` | `SessionResponse`, `OfflineMissionGrantDto`, `GRANT_JSON_KEY` |
| A2 | `main/.../auth/LoginUseCase.kt` | `RESCUER_ROLE`, `validateGrant` (timestamps, sanity check), identity check in `signInAndBind` |
| A3 | `main/.../auth/LoginUseCase.kt` | role check in `signInAndBind`, `toAuthError` |
| A4 | `main/.../auth/LoginUseCase.kt` | `BackendError.toAuthError()` |
| A4 | `main/.../data/backend/KtorAuthBackend.kt` | `execute` (`ErrorBody` parsing) |
| A5 | `main/.../data/backend/KtorAuthBackend.kt` | `logout`, `LOGOUT_PATH` |
| A5 | `main/.../auth/LogoutUseCase.kt` | `invoke` (order, timeout, after role rejection) |
| all | `debug/.../data/backend/FakeAuthBackend.kt` | debug fake that implements this contract |

Tests that pin the contract: `app/src/test/.../data/backend/KtorAuthBackendTest.kt` (exact path,
header, JSON and error codes) and `app/src/test/.../auth/LoginUseCaseTest.kt`.

---

## Türkçe

**Kime:** Jira OSP-60'ın backend geliştiricisine (`POST /api/v1/auth/session`,
`POST /api/v1/auth/logout`).
**Neden:** OSP-74 (Android Saha Kurtarıcısı girişi), OSP-60 henüz yokken yazıldı. Uygulama aşağıdaki
sözleşmeyi varsayıyor. Sizin uygulamanız farklıysa Android sorumlusuna bildirin. Her varsayım kodda
da işaretli; listelemek için şunu çalıştırın:

```sh
grep -rn "OSP-60 ASSUMPTION" app/src
```

OSP-60 yayına alınana kadar debug derlemeleri bu sözleşmeye uyan sahte bir backend kullanır
(`FakeAuthBackend`, yalnızca debug).

### A1. `POST /api/v1/auth/session`: istek
Uygulama bu isteği, Supabase e-posta/parola girişi başarılı olduktan hemen sonra gönderir:

```http
POST /api/v1/auth/session
Authorization: Bearer <supabase access_token>
Content-Type: application/json

{"device_id":"3f1c2b9e-7a4d-4e8b-9c1a-2d5e6f708192","device_name":"Samsung SM-A546B"}
```
- `device_id`, uygulama ilk açıldığında üretilen rastgele bir v4 UUID'dir. Çıkışta değişmez;
  yalnızca uygulama yeniden yüklenince ya da uygulamanın yerel oturum dosyası bozulursa (o zaman
  yeniden üretilir) değişir. Hiçbir zaman IMEI veya ANDROID_ID değildir.
- `device_name`, `"<üretici> <model>"` biçimindedir; en fazla 100 karakterdir.

### A2. `POST /api/v1/auth/session`: 200 yanıtı
**Kurtarıcı** için:
```json
{
  "user_id": "8d0f6a52-1c3e-4b7a-9f21-5e6d7c8b9a01",
  "role": "rescuer",
  "device_id": "3f1c2b9e-7a4d-4e8b-9c1a-2d5e6f708192",
  "offline_mission_grant": {
    "user_id": "8d0f6a52-1c3e-4b7a-9f21-5e6d7c8b9a01",
    "device_id": "3f1c2b9e-7a4d-4e8b-9c1a-2d5e6f708192",
    "authenticated_at": "2026-11-19T10:00:00Z",
    "expires_at": "2026-11-22T10:00:00Z"
  }
}
```
**Koordinatör veya yönetici** için (200 dönerseniz) grant alanı yoktur (ya da `null`'dır):
```json
{"user_id":"…","role":"coordinator","device_id":"3f1c2b9e-7a4d-4e8b-9c1a-2d5e6f708192"}
```
- **Grant anahtarı:** `offline_mission_grant`.
- **Rol değerleri:** `role`, `public.user_profiles` tablosundaki küçük harfli değerdir: `rescuer`,
  `coordinator` veya `administrator`. Uygulama karar verirken **yalnızca** bu alana bakar; JWT'deki
  `user_role` claim'ini kullanmaz.
- **Zaman damgaları:** `authenticated_at` ve `expires_at` **ofsetli** ISO-8601'dir; örneğin
  `2026-11-19T10:00:00Z` veya `2026-11-19T10:00:00+00:00`. Ofseti olmayan değer reddedilir.
- **Bilinmeyen alanlar:** fazladan gelen alanlar yok sayılır. Yukarıdaki alanlar zorunludur;
  yalnızca kurtarıcı olmayanlarda grant zorunlu değildir.
- **İstemci tarafı kontrol:** aşağıdakilerin hepsi sağlanmazsa uygulama grant'i reddeder ("geçersiz
  görev yetkisi") ve ardından tam çıkış yapar:
  - `user_id ==` uygulamanın gönderdiği access token'ın Supabase kullanıcı kimliği (JWT `sub`) ve
    `device_id ==` uygulamanın gönderdiği `device_id` (her 200 yanıtında, kurtarıcı olmayanlarda da
    kontrol edilir)
  - `grant.user_id == user_id`
  - `grant.device_id ==` uygulamanın gönderdiği `device_id`
  - `0 < expires_at − authenticated_at ≤ 72 saat` (60 sn tolerans)
  - `expires_at` cihazın şu anki saatinden sonradır ve `authenticated_at` bu saatten en fazla 5 dk
    sonradır (sunucu ve cihaz saatleri en fazla 5 dk farklı olabilir)

### A3. Koordinatör / yönetici
Uygulama aşağıdakilerden **ikisini de** kabul eder; ikisinin sonucu aynıdır: rol hatası gösterilir,
ardından çıkış yapılır.
- `role` ≠ `rescuer` ve grant olmadan 200;
- 403 `ROLE_FORBIDDEN`.

### A4. Hatalar
Hatalar ortak hata sözleşmesine uyar: `{ "error_code": "...", "message": "..." }`
(`connect-backend/src/types/errors.ts`). Örnek:
```json
{"error_code":"DEVICE_REVOKED","message":"Device has been revoked"}
```
`message` yalnızca debug derlemede loglanır, kullanıcıya hiç
gösterilmez. Uygulama `error_code`'a göre yerelleştirilmiş metin gösterir:

| HTTP | `error_code` | Uygulamanın tepkisi |
|---|---|---|
| 401 | `UNAUTHENTICATED` | "oturum doğrulanamadı" |
| 403 | `ROLE_FORBIDDEN` | rol hatası (A3) |
| 403 | `ACCOUNT_DISABLED` | "hesap devre dışı". Profil yoksa veya pasifse (`user_role` = null) bunun döneceği varsayıldı |
| 403 | `DEVICE_REVOKED` | "cihaz yetkisi kaldırıldı" |
| 403 | `SESSION_NOT_BOUND` | genel sunucu hatası |
| 429 | `RATE_LIMITED` (her 429) | "çok fazla deneme" |
| 400 | `VALIDATION_ERROR` | "istek reddedildi, uygulamayı güncelleyin" |
| 404 / 5xx | `NOT_FOUND` / `INTERNAL_ERROR` / bilinmeyen / JSON olmayan gövde | genel sunucu hatası |
| 2xx | çözümlenemeyen gövde | "geçersiz görev yetkisi" |

Supabase girişinden sonraki her hata tam çıkışı (A5) tetikler.

### A5. `POST /api/v1/auth/logout`
```http
POST /api/v1/auth/logout
Authorization: Bearer <supabase access_token>
```
- **Gövde yoktur.** Her 2xx başarı sayılır; yanıt gövdesi yok sayılır.
- **En iyi çaba:** çağrının 5 sn zaman aşımı vardır ve hatalar yok sayılır. Yerel silme her durumda
  yapılır. Access token'ın **süresi dolmuş olabilir**.
- **Ne zaman çağrılır:**
  - "Görevi Bitir / Çıkış Yap" seçildiğinde;
  - **reddedilen bir girişten hemen sonra da** (rol reddi, herhangi bir backend hatası, geçersiz
    grant). Çünkü `/auth/session` oturumu zaten bağlamış olabilir. Bu yüzden reddedilen bir
    koordinatör için de LOGOUT denetim kaydı oluşur. Sorun olursa bize bildirin.
- **Sıra:** uygulama önce yerel grant'i siler, sonra bellekte tuttuğu access token ile
  `/auth/logout` ve Supabase `/logout` (scope `local`) çağırır, en sonda yerel token'ları siler.

### Varsayımların Android kodundaki yerleri

| # | Dosya (`app/src/...`) | Sembol |
|---|---|---|
| A1 | `main/.../data/backend/AuthDtos.kt` | `SessionRequest` |
| A1 | `main/.../data/backend/KtorAuthBackend.kt` | `bindSession`, `SESSION_PATH` |
| A2 | `main/.../data/backend/AuthDtos.kt` | `SessionResponse`, `OfflineMissionGrantDto`, `GRANT_JSON_KEY` |
| A2 | `main/.../auth/LoginUseCase.kt` | `RESCUER_ROLE`, `validateGrant` (zaman damgaları, kontrol), `signInAndBind` içindeki kimlik kontrolü |
| A3 | `main/.../auth/LoginUseCase.kt` | `signInAndBind` içindeki rol kontrolü, `toAuthError` |
| A4 | `main/.../auth/LoginUseCase.kt` | `BackendError.toAuthError()` |
| A4 | `main/.../data/backend/KtorAuthBackend.kt` | `execute` (`ErrorBody` çözümleme) |
| A5 | `main/.../data/backend/KtorAuthBackend.kt` | `logout`, `LOGOUT_PATH` |
| A5 | `main/.../auth/LogoutUseCase.kt` | `invoke` (sıra, zaman aşımı, rol reddinden sonra) |
| hepsi | `debug/.../data/backend/FakeAuthBackend.kt` | bu sözleşmeyi uygulayan debug sahte backend'i |

Sözleşmeyi sabitleyen testler: `app/src/test/.../data/backend/KtorAuthBackendTest.kt` (birebir yol,
başlık, JSON ve hata kodları) ve `app/src/test/.../auth/LoginUseCaseTest.kt`.
