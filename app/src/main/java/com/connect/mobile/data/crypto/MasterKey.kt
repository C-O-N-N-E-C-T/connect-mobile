package com.connect.mobile.data.crypto

import com.google.crypto.tink.Aead
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.google.crypto.tink.integration.android.AndroidKeystore
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException

/**
 * Cihazdan dışarı çıkarılamayan bir ana anahtarla (Android Keystore, AES-256-GCM) şifreleme yapan
 * AEAD kaynağı (OSP-75, FR-R-11 / FR-R-15).
 *
 * Arayüz olmasının nedeni: JVM testlerinde Keystore yoktur; testler Tink'in yazılımsal AES-GCM'ini
 * veya hata üreten sahte bir uygulamayı enjekte eder.
 */
interface MasterKey {
    /** Anahtar yoksa üretir (ilk kurulum), sonra AEAD'i döndürür. */
    @Throws(GeneralSecurityException::class)
    fun getOrCreateAead(): Aead

    /**
     * Anahtar ZATEN varsa AEAD'i döndürür; yoksa [MasterKeyMissingException] atar. Şifreli veri diskte
     * varken yeni anahtar üretmek o veriyi sessizce kullanılamaz hale getirirdi (DB anahtarı için yasak).
     */
    @Throws(GeneralSecurityException::class)
    fun getExistingAead(): Aead

    /**
     * Anahtarı Keystore'dan siler. YALNIZCA anahtar kalıcı olarak kullanılamaz olduğunda (bkz.
     * [isPermanentKeyFailure]) oturum deposu için çağrılır; DB anahtarı için asla çağrılmaz.
     */
    @Throws(GeneralSecurityException::class)
    fun deleteKey()
}

/** Keystore'da beklenen ana anahtar yok (ör. Keystore sıfırlandı). Geçici hata DEĞİLDİR. */
class MasterKeyMissingException(alias: String) :
    GeneralSecurityException("master key not found in Android Keystore: $alias")

/**
 * Tink'in doğrudan [AndroidKeystore] API'siyle ana anahtar. Bilinçli olarak `AndroidKeysetManager` /
 * `AndroidKeystoreKmsClient` KULLANILMAZ: onlar Keystore hatasında anahtarı düz metin saklamaya
 * düşebilir. Burada Keystore kullanılamazsa hata yükselir (fail-closed), düz metne asla düşülmez.
 *
 * StrongBox zorunlu değildir (Tink'in varsayılan AES-256-GCM spec'i, kullanıcı doğrulaması gerekmez:
 * uygulama kilit ekranı olmadan da arka planda çalışabilmeli).
 */
class AndroidKeystoreMasterKey(private val alias: String) : MasterKey {

    override fun getOrCreateAead(): Aead = synchronized(KEYSTORE_LOCK) {
        // hasKey + generate tek kilit altında: iki iş parçacığı aynı alias için iki kez anahtar
        // üretirse ikincisi birincinin üzerine yazar ve ilk anahtarla şifrelenen veri kaybolur.
        if (!AndroidKeystore.hasKey(alias)) AndroidKeystore.generateNewAes256GcmKey(alias)
        AndroidKeystore.getAead(alias)
    }

    override fun getExistingAead(): Aead = synchronized(KEYSTORE_LOCK) {
        if (!AndroidKeystore.hasKey(alias)) throw MasterKeyMissingException(alias)
        AndroidKeystore.getAead(alias)
    }

    override fun deleteKey() = synchronized(KEYSTORE_LOCK) {
        if (AndroidKeystore.hasKey(alias)) AndroidKeystore.deleteKey(alias)
    }

    companion object {
        /** Keystore süreç genelinde tek olduğu için kilit de süreç genelinde tektir. */
        private val KEYSTORE_LOCK = Any()

        /** Oturum deposu ve DB anahtarı ayrı anahtarlar kullanır: biri kaybolsa diğeri etkilenmez. */
        const val SESSION_ALIAS = "connect_session_master_key"
        const val DB_ALIAS = "connect_db_master_key"
    }
}

/**
 * Şifre çözme hatasının "veri gerçekten bozuk / başka anahtarla şifrelenmiş" anlamına gelip
 * gelmediği. Yalnızca etiket (tag) / dolgu hataları bozulmadır; diğer her şey (Keystore meşgul,
 * ProviderException, anahtar geçici olarak yüklenemedi) GEÇİCİ sayılır ve veri silinmez.
 */
internal fun Throwable.isCiphertextCorruption(): Boolean =
    this is AEADBadTagException || this is BadPaddingException

/**
 * Anahtarın KALICI olarak kullanılamaz olduğu durumlar (yeniden denemek işe yaramaz):
 * - [KeyPermanentlyInvalidatedException]: anahtar geçersiz kılındı (ör. kilit ekranı değişti);
 * - [UnrecoverableKeyException]: Keystore girdisi okunamıyor;
 * - [ClassCastException]: Tink `AndroidKeystore` AeadImpl'i girdiyi `SecretKey`'e dönüştürürken
 *   (alias'ta başka türde bir girdi varsa) bunu atar.
 * Neden zinciri de taranır: Keystore bunları bazen ProviderException içinde sarar.
 * Bu kontrol [isTransientCryptoFailure]'dan ÖNCE yapılmalıdır (ikisi de GeneralSecurityException olabilir).
 */
internal fun Throwable.isPermanentKeyFailure(): Boolean =
    generateSequence(this) { it.cause }.take(8).any {
        it is KeyPermanentlyInvalidatedException || it is UnrecoverableKeyException || it is ClassCastException
    }

/** Keystore/AEAD işlemlerinde beklenen geçici hata türleri (çağıran IOException'a çevirir). */
internal fun Throwable.isTransientCryptoFailure(): Boolean =
    this is GeneralSecurityException || this is ProviderException || this is IllegalStateException
