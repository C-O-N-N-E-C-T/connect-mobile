package com.connect.mobile.data.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKey
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.subtle.AesGcmJce
import com.google.crypto.tink.util.SecretBytes
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * JVM testleri için kripto yardımcıları (OSP-75). Android Keystore JVM'de yoktur; yerine Tink'in
 * yazılımsal AES-256-GCM'i kullanılır (Keystore AEAD'i ile aynı biçim: 12 bayt IV + şifreli metin +
 * 16 bayt etiket). Sınanan mantık (hata sınıflandırması, dosya içeriği, anahtar akışı) aynıdır.
 */
object TestCrypto {
    init {
        AeadConfig.register()
    }

    /**
     * Yeni rastgele anahtarlı yazılımsal AES-256-GCM. Önek yok (NO_PREFIX) ve doğrudan [AesGcmJce]:
     * etiket hatasında Android Keystore'daki Tink `AndroidKeystore.getAead` gibi AEADBadTagException
     * atar. (Keyset/KeysetHandle sarmalayıcısı bunu genel GeneralSecurityException'a çevirirdi; o
     * yalnızca [keysetAead] ile ayrıca sınanır.)
     */
    fun softwareAead(): Aead = AesGcmJce.create(
        AesGcmKey.builder()
            .setParameters(
                AesGcmParameters.builder()
                    .setKeySizeBytes(32)
                    .setIvSizeBytes(12)
                    .setTagSizeBytes(16)
                    .setVariant(AesGcmParameters.Variant.NO_PREFIX)
                    .build(),
            )
            .setKeyBytes(SecretBytes.randomBytes(32))
            .build(),
    )

    /** Keyset tabanlı AEAD (güncel, Configuration alan generateNew/getPrimitive overload'ları). */
    fun keysetAead(): Aead {
        val config = RegistryConfiguration.get()
        return KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM, config)
            .getPrimitive(config, Aead::class.java)
    }
}

/** Keystore'un geçici hatasını taklit eder (ör. keystore2 meşgul): ne etiket ne dolgu hatası. */
class TransientFailingAead : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        throw ProviderException("keystore busy")

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
        throw GeneralSecurityException("keystore temporarily unavailable")
}

/**
 * [AndroidKeystoreMasterKey] yerine: anahtar "var/yok" durumu, üretim sayısı ve geçici hata
 * enjekte edilebilir.
 */
class FakeMasterKey(var present: Boolean = false) : MasterKey {
    private var aead: Aead? = null
    var generated = 0
    var failure: GeneralSecurityException? = null
    var deleted = 0

    /** Gerçekte Keystore'un sıfırlanması: anahtar kaybolur, yenisi farklı olur. */
    fun lose() {
        present = false
        aead = null
    }

    override fun getOrCreateAead(): Aead {
        failure?.let { throw it }
        if (!present || aead == null) {
            aead = TestCrypto.softwareAead()
            present = true
            generated++
        }
        return checkNotNull(aead)
    }

    override fun getExistingAead(): Aead {
        failure?.let { throw it }
        if (!present || aead == null) throw MasterKeyMissingException("test")
        return checkNotNull(aead)
    }

    override fun deleteKey() {
        deleted++
        lose()
    }
}

/** Bir bayt dizisinin içinde [needle] (UTF-8) düz metin olarak geçiyor mu. */
fun ByteArray.containsUtf8(needle: String): Boolean {
    val n = needle.toByteArray(Charsets.UTF_8)
    if (n.isEmpty() || n.size > size) return false
    outer@ for (i in 0..size - n.size) {
        for (j in n.indices) if (this[i + j] != n[j]) continue@outer
        return true
    }
    return false
}
