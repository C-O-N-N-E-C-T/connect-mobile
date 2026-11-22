package com.connect.mobile.data.session

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import okio.Buffer
import java.io.File
import java.io.FileOutputStream

/**
 * OSP-74'ün düz metin deposunu (`connect_session.preferences_pb`) şifreli depoya taşır (OSP-75).
 *
 * DataStore'un [DataMigration] sözleşmesi taşımayı güvenli kılar:
 * 1. [migrate] eski dosyayı okur ve mevcut (şifreli) içerikle birleştirir;
 * 2. DataStore sonucu şifreli dosyaya atomik yazar (geçici dosya + yeniden adlandırma);
 * 3. [cleanUp] YALNIZCA bu yazma başarılı olduktan sonra çağrılır ve eski dosyayı siler.
 *
 * Yarıda kesilme: 2'den önce kesilirse hiçbir şey değişmemiştir; 2 ile 3 arasında kesilirse bir sonraki
 * açılışta eski dosya hâlâ vardır, birleştirme şifreli depodaki değerleri korur (aynı değerler, yazma
 * gerekmez) ve 3 tekrarlanır. Bu yüzden taşıma idempotenttir; eski dosya yokken hiç çalışmaz.
 *
 * Silinemeyen eski dosya (D3): taşıma başarılı olunca şifreli depoya [MIGRATED] bayrağı yazılır.
 * Bayrak varken birleştirme bir daha YAPILMAZ, yalnızca silme denenir; böylece logout (clearAuth)
 * sonrası eski düz metin kopyadaki oturum/grant geri gelemez. clearAuth bu bayrağı silmez (yalnızca
 * kendi anahtar listesini siler). Silme yine başarısızsa dosya sıfır bayta indirilir.
 *
 * Bu sınıf eski dosya için ikinci bir DataStore AÇMAZ (aynı süreçte iki DataStore aynı dosyayı tutamaz);
 * dosyayı doğrudan DataStore'un kendi biçim okuyucusuyla ([PreferencesSerializer]) çözer.
 */
class LegacyPlaintextSessionMigration(private val legacyFile: File) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = legacyFile.exists()

    override suspend fun migrate(currentData: Preferences): Preferences {
        // Daha önce taşındı ama eski dosya silinemedi: içeriği yok say, cleanUp silmeyi tekrar dener.
        if (currentData[MIGRATED] == true) return currentData
        // Okuma G/Ç hatası (IOException) yukarı yükselir: taşıma başarısız sayılır, eski dosya silinmez,
        // bir sonraki okuma yeniden dener.
        val legacy = try {
            PreferencesSerializer.readFrom(Buffer().write(legacyFile.readBytes()))
        } catch (e: CorruptionException) {
            // Bozuk eski dosya OSP-74'te de "boş" sayılıyordu (R4); taşınacak bir şey yok, cleanUp siler.
            emptyPreferences()
        }
        val merged = currentData.toMutablePreferences()
        for ((key, value) in legacy.asMap()) {
            // Şifreli depoda zaten olan değer kazanır: yarıda kesilmiş bir taşımadan sonra ya da taşıma
            // sonrası yazılmış yeni bir değeri eski düz metin kopya ezmemeli.
            if (!merged.contains(key)) {
                @Suppress("UNCHECKED_CAST")
                merged[key as Preferences.Key<Any>] = value
            }
        }
        merged[MIGRATED] = true
        return merged.toPreferences()
    }

    override suspend fun cleanUp() {
        // Hata atma: silme başarısızsa bir sonraki açılış birleştirmeyi (değişiklik yok) ve silmeyi
        // tekrarlar. DataStore'un yarım kalmış geçici dosyası da düz metin içerebilir, o da silinir.
        deleteOrTruncate(legacyFile)
        deleteOrTruncate(File(legacyFile.path + ".tmp"))
    }

    /** Silinemezse en azından düz metin içeriği sıfırlanır (boş dosya = boş tercih, sızıntı yok). */
    private fun deleteOrTruncate(file: File) {
        if (!file.exists() || file.delete()) return
        try {
            FileOutputStream(file).close()
        } catch (e: Exception) {
            // Bayrak sayesinde içerik yine de bir daha taşınmaz; bir sonraki açılış tekrar dener.
        }
    }

    companion object {
        /** Şifreli depoda "eski dosya taşındı" bayrağı; clearAuth'un anahtar listesinde YOK. */
        internal val MIGRATED = booleanPreferencesKey("legacy_plaintext_migrated")
    }
}
