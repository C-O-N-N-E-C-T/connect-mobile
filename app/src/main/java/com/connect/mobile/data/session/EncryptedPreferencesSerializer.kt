package com.connect.mobile.data.session

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.emptyPreferences
import com.connect.mobile.data.crypto.isCiphertextCorruption
import com.connect.mobile.data.crypto.isPermanentKeyFailure
import com.connect.mobile.data.crypto.isTransientCryptoFailure
import com.google.crypto.tink.Aead
import kotlinx.coroutines.CancellationException
import okio.Buffer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException

/** Testte sahte/yazılımsal AEAD, uygulamada Keystore ana anahtarı verir. */
fun interface AeadSupplier {
    @Throws(GeneralSecurityException::class)
    fun get(): Aead

    /**
     * Ana anahtar kalıcı olarak kullanılamaz olduğunda çağrılır: anahtar silinir ki bir sonraki [get]
     * yenisini üretsin (oturum deposu fail-closed boşalır, yeniden giriş gerekir).
     */
    @Throws(GeneralSecurityException::class)
    fun discardKey() = Unit
}

/**
 * DataStore dosyasının TAMAMINI Tink AEAD ile şifreleyen [Serializer] (OSP-75, FR-R-15).
 *
 * İçerik önce DataStore'un kendi protobuf biçimiyle ([PreferencesSerializer]) baytlara çevrilir, sonra
 * tek parça şifrelenir. Böylece değerler gibi anahtar adları ("supabase_session", "grant_*") da diskte
 * görünmez. Dosya biçimi: `[1 bayt sürüm][AEAD şifreli metin]`; associated data dosyanın amacını bağlar
 * (başka bir şifreli dosya bu dosyanın yerine konursa çözülemez).
 *
 * Hata sınıflandırması (veri kaybını önlemek için kritik):
 * - Gerçek bozulma (yanlış sürüm baytı, kısa dosya, AEAD etiket/dolgu hatası, bozuk protobuf)
 *   -> [CorruptionException]: DataStore'un bozulma işleyicisi devreye girer.
 * - Ana anahtar kalıcı olarak kullanılamaz (KeyPermanentlyInvalidated / UnrecoverableKey /
 *   ClassCast) -> anahtar silinir; okumada [CorruptionException] (depo boşalır, yeniden giriş),
 *   yazmada [IOException] (sonraki deneme yeni anahtarla dosyanın tamamını yazar).
 * - Keystore geçici hatası (diğer GeneralSecurityException, ProviderException) -> [IOException]:
 *   bozulma işleyicisi ÇALIŞMAZ, dosya SİLİNMEZ; bir sonraki okuma yeniden dener.
 */
class EncryptedPreferencesSerializer(
    private val aeadSupplier: AeadSupplier,
    private val associatedData: ByteArray = ASSOCIATED_DATA,
) : Serializer<Preferences> {

    // Başarılı ilk Keystore erişiminden sonra AEAD önbelleğe alınır (her okuma/yazmada Keystore'a
    // tekrar gitmemek için). Hata durumunda önbellek dolmaz, sonraki çağrı yeniden dener.
    @Volatile
    private var cached: Aead? = null

    override val defaultValue: Preferences = emptyPreferences()

    override suspend fun readFrom(input: InputStream): Preferences {
        val bytes = input.readBytes()
        if (bytes.size < MIN_SIZE || bytes[0] != FORMAT_VERSION) {
            // Kısa/yanlış sürümlü dosya Tink'e hiç verilmez: yazılımsal AES-GCM "ciphertext too short"
            // için genel GeneralSecurityException atar ve bu yanlışlıkla "geçici" sayılırdı.
            throw CorruptionException("encrypted session file has an unknown format")
        }
        val plaintext = crypto("decrypt", reading = true) { aead().decrypt(bytes.copyOfRange(1, bytes.size), associatedData) }
        try {
            // Bozuk protobuf için PreferencesSerializer kendisi CorruptionException atar.
            return PreferencesSerializer.readFrom(Buffer().write(plaintext))
        } finally {
            // Çözülmüş baytları (token içerir) bellekte bekletmemek için sıfırla.
            plaintext.fill(0)
        }
    }

    override suspend fun writeTo(t: Preferences, output: OutputStream) {
        val buffer = Buffer()
        PreferencesSerializer.writeTo(t, buffer)
        val plaintext = buffer.readByteArray()
        try {
            val ciphertext = crypto("encrypt", reading = false) { aead().encrypt(plaintext, associatedData) }
            // DataStore geçici dosyaya yazar ve başarılıysa atomik olarak yerine taşır; şifreleme
            // başarısız olursa buraya hiç gelinmez ve eski dosya olduğu gibi kalır.
            output.write(byteArrayOf(FORMAT_VERSION))
            output.write(ciphertext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun aead(): Aead = cached ?: aeadSupplier.get().also { cached = it }

    private inline fun <T> crypto(op: String, reading: Boolean, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        when {
            e.isPermanentKeyFailure() -> {
                // Önbellekteki AEAD artık işe yaramaz; anahtar silinir, sonraki erişim yenisini üretir.
                cached = null
                try {
                    aeadSupplier.discardKey()
                } catch (d: Exception) {
                    // Silinemese bile fail-closed: aşağıdaki hata yine yükselir.
                }
                if (reading) throw CorruptionException("session master key permanently unusable", e)
                throw IOException("session master key permanently unusable", e)
            }
            e.isCiphertextCorruption() -> throw CorruptionException("session file cannot be authenticated", e)
            // Mesajda içerik yok; yalnızca işlem adı ve hata türü.
            e.isTransientCryptoFailure() -> throw IOException("session $op failed: ${e.javaClass.simpleName}", e)
            else -> throw e
        }
    }

    companion object {
        /** Dosya biçimi sürümü; değişirse eski dosya bozuk sayılır (yeniden giriş gerekir). */
        const val FORMAT_VERSION: Byte = 1

        /** Dosyanın amacını şifreye bağlar; sürüm de içindedir. */
        val ASSOCIATED_DATA: ByteArray = "connect_session.v1".toByteArray(Charsets.UTF_8)

        /** Sürüm baytı + 12 bayt IV + 16 bayt etiket; bundan kısa dosya geçerli olamaz. */
        private const val MIN_SIZE = 1 + 12 + 16
    }
}
