package com.connect.mobile.data.db

import com.connect.mobile.data.crypto.MasterKey
import com.connect.mobile.data.crypto.MasterKeyMissingException
import com.connect.mobile.data.crypto.isCiphertextCorruption
import com.connect.mobile.data.crypto.isPermanentKeyFailure
import com.connect.mobile.data.crypto.isTransientCryptoFailure
import com.google.crypto.tink.Aead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

/**
 * SQLCipher veritabanı parolası (OSP-75, FR-R-11 / AC-28).
 *
 * - Parola 32 rastgele bayttır ([SecureRandom]); diske ASLA düz metin yazılmaz.
 * - Android Keystore'daki dışarı çıkarılamaz AES-256-GCM ana anahtarla sarılır (wrap) ve [keyFile]'a
 *   atomik yazılır; DB açılırken yalnızca bellekte çözülür.
 * - Dosya oturum deposundan AYRIDIR: clearAuth / logout / HTTP 403 / hesap kapatma bu dosyaya hiç
 *   dokunmaz (bu sınıfa erişimleri yoktur). Silme yalnızca elle kullanıcı/cihaz sıfırlamasında,
 *   [deleteForManualReset] ile yapılır. Senkronlanmamış kazazede kayıtları böylece korunur.
 * - Sarılmış dosya var ama açılamıyorsa sessizce YENİ anahtar üretilmez: yeni anahtar eski DB'yi
 *   kalıcı olarak okunamaz yapardı. Bunun yerine hata verilir:
 *   - [DbKeyUnrecoverableException]: dosya bozuk / ana anahtar kayıp (elle sıfırlama gerekir);
 *   - düz [IOException]: Keystore geçici hatası (yeniden denenebilir, dosya silinmez).
 *
 * [encryptedDataExists]: anahtar dosyası YOKKEN şifreli veri (ör. DB dosyası) varsa yeni anahtar
 * üretmek de aynı veri kaybına yol açar. Bilerek varsayılanı yok: her çağıran (Room/E8 dahil) DB
 * dosyasının varlığını açıkça bildirmek zorunda.
 */
class DbKeyProvider(
    private val keyFile: File,
    private val masterKey: MasterKey,
    private val encryptedDataExists: () -> Boolean,
    private val random: SecureRandom = SecureRandom(),
) {
    // Aynı süreçte iki eşzamanlı ilk çağrı iki farklı anahtar üretip birbirinin üzerine yazmasın.
    private val mutex = Mutex()

    /**
     * 32 baytlık ham DB anahtarının YENİ bir kopyası. Çağıran kullandıktan sonra `fill(0)` ile siler.
     */
    suspend fun getOrCreateKey(): ByteArray = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (keyFile.exists()) readExisting() else createNew()
        }
    }

    /**
     * SQLCipher'ın ham anahtar biçimi: `x'<64 onaltılık>'` (UTF-8 bayt). Bu biçimde SQLCipher PBKDF2
     * türetmesini atlar; anahtar zaten 256 bit rastgele olduğu için KDF gereksizdir. Bayt dizisi döner
     * (String değil) ki çağıran kullandıktan sonra silebilsin.
     */
    suspend fun sqlCipherPassphrase(): ByteArray {
        val key = getOrCreateKey()
        try {
            return toSqlCipherRawKey(key)
        } finally {
            key.fill(0)
        }
    }

    /**
     * YALNIZCA elle kullanıcı/cihaz sıfırlaması için (henüz çağıran yok). Logout, 403 veya hesap
     * kapatma akışlarından çağrılmamalıdır. Sonrasında eski DB okunamaz; DB de silinmelidir.
     */
    suspend fun deleteForManualReset() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (keyFile.exists() && !keyFile.delete()) throw IOException("db key file could not be deleted")
            tmpFile().delete()
        }
    }

    private fun readExisting(): ByteArray {
        val wrapped = keyFile.readBytes()
        if (wrapped.size < MIN_SIZE || wrapped[0] != FORMAT_VERSION) {
            throw DbKeyUnrecoverableException("wrapped db key has an unknown format")
        }
        val aead = keystore { masterKey.getExistingAead() }
        val key = keystore { aead.decrypt(wrapped.copyOfRange(1, wrapped.size), ASSOCIATED_DATA) }
        if (key.size != KEY_SIZE) {
            key.fill(0)
            throw DbKeyUnrecoverableException("wrapped db key has a wrong length")
        }
        return key
    }

    private fun createNew(): ByteArray {
        if (encryptedDataExists()) {
            throw DbKeyUnrecoverableException("db key file is missing while encrypted data exists")
        }
        val aead = keystore { masterKey.getOrCreateAead() }
        val key = ByteArray(KEY_SIZE).also(random::nextBytes)
        try {
            val wrapped = byteArrayOf(FORMAT_VERSION) + keystore { aead.encrypt(key, ASSOCIATED_DATA) }
            writeAtomically(wrapped)
            // Önce yaz, sonra doğrula: diskteki sarılmış anahtar gerçekten aynı anahtara çözülmeli.
            // Anahtar henüz hiçbir veriyi şifrelemediği için doğrulama başarısızsa dosyayı silmek güvenli.
            val check = try {
                readExisting()
            } catch (e: Exception) {
                keyFile.delete()
                throw IOException("db key verification failed", e)
            }
            val same = check.contentEquals(key)
            check.fill(0)
            if (!same) {
                keyFile.delete()
                throw IOException("db key verification failed")
            }
            return key
        } catch (e: Throwable) {
            key.fill(0)
            throw e
        }
    }

    /** Geçici dosya + fsync + yeniden adlandırma: yarım yazılmış anahtar dosyası asla oluşmaz. */
    private fun writeAtomically(bytes: ByteArray) {
        keyFile.parentFile?.mkdirs()
        val tmp = tmpFile()
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(keyFile)) {
            tmp.delete()
            throw IOException("db key file could not be written")
        }
        syncDirectory(keyFile.absoluteFile.parentFile)
    }

    /**
     * Yeniden adlandırmanın (dizin girdisinin) da diske işlenmesi için üst dizini fsync'ler; aksi halde
     * ani güç kaybında dosya adı kaybolabilir. Dizin açmayı desteklemeyen dosya sistemlerinde sessizce
     * geçilir: dosyanın içeriği zaten fsync'lendi.
     */
    private fun syncDirectory(dir: File?) {
        if (dir == null) return
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            // desteklenmiyor: geç
        }
    }

    private fun tmpFile() = File(keyFile.path + ".tmp")

    private inline fun <T> keystore(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: MasterKeyMissingException) {
        // Sarılmış dosya var ama Keystore anahtarı yok: anahtar bir daha çözülemez.
        throw DbKeyUnrecoverableException("db master key is missing", e)
    } catch (e: Exception) {
        when {
            // Kalıcı Keystore hatası: anahtar bir daha çözülemez. DB anahtarı ASLA yeniden üretilmez,
            // Keystore anahtarı da silinmez (elle sıfırlamaya kadar veri kurtarma şansı korunur).
            e.isPermanentKeyFailure() -> throw DbKeyUnrecoverableException("db master key permanently unusable", e)
            e.isCiphertextCorruption() -> throw DbKeyUnrecoverableException("wrapped db key cannot be authenticated", e)
            e.isTransientCryptoFailure() -> throw IOException("keystore operation failed: ${e.javaClass.simpleName}", e)
            else -> throw e
        }
    }

    companion object {
        /** -> no_backup/connect_db_key.enc (yedeklemeye hiç girmez; allowBackup zaten false). */
        const val FILE_NAME = "connect_db_key.enc"

        const val KEY_SIZE = 32
        const val FORMAT_VERSION: Byte = 1
        val ASSOCIATED_DATA: ByteArray = "connect_db_key.v1".toByteArray(Charsets.UTF_8)

        /** Sürüm baytı + 12 bayt IV + 16 bayt etiket + en az 1 bayt. */
        private const val MIN_SIZE = 1 + 12 + 16 + 1

        /** `x'…'` ham anahtar biçimi; ayrı fonksiyon ki testler ve Room fabrikası doğrudan kullanabilsin. */
        fun toSqlCipherRawKey(key: ByteArray): ByteArray {
            require(key.size == KEY_SIZE) { "db key must be $KEY_SIZE bytes" }
            val out = ByteArray(3 + KEY_SIZE * 2)
            out[0] = 'x'.code.toByte()
            out[1] = '\''.code.toByte()
            for (i in key.indices) {
                val v = key[i].toInt() and 0xFF
                out[2 + i * 2] = HEX[v ushr 4].code.toByte()
                out[3 + i * 2] = HEX[v and 0x0F].code.toByte()
            }
            out[out.size - 1] = '\''.code.toByte()
            return out
        }

        private const val HEX = "0123456789abcdef"
    }
}

/**
 * DB anahtarı kurtarılamaz (bozuk dosya / kayıp ana anahtar / anahtar dosyası eksik ama veri var).
 * Otomatik çözüm YOK: yeni anahtar üretmek veri kaybı olurdu; yalnızca elle sıfırlama.
 */
class DbKeyUnrecoverableException(message: String, cause: Throwable? = null) : IOException(message, cause)
