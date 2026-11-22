package com.connect.mobile.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.core.FileStorage
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Şifreli oturum ve grant deposu (OSP-75, FR-R-15): device_id, supabase-kt oturumu ve 72 saatlik
 * OfflineMissionGrant, dosyanın tamamı Tink AEAD ile şifrelenmiş bir Jetpack DataStore'da tutulur.
 * EncryptedSharedPreferences kullanılmaz.
 *
 * İş mantığı (anahtar listeleri, clearAuth'un device_id'ye dokunmaması, atomik get-or-create)
 * değişmeden [DataStoreSessionStore]'dan gelir; burada yalnızca DataStore'un depolama katmanı
 * şifreli seri hale getiriciyle kurulur. Böylece [SessionStore] sözleşmesi ve çağıran kod aynı kalır.
 */
object EncryptedSessionStore {
    /** -> files/datastore/connect_session.enc */
    const val FILE_NAME = "connect_session.enc"

    /**
     * [legacyFile] verilirse (ve varsa) OSP-74'ün düz metin dosyası ilk açılışta taşınıp silinir.
     *
     * Bozulma politikası OSP-74 ile aynıdır (R4): gerçekten bozuk (doğrulanamayan) bir dosya her
     * açılışta çökmek yerine boş içerikle değiştirilir; giriş gerekir, device_id yeniden üretilir.
     * Keystore'un GEÇİCİ hataları bozulma sayılmaz ([EncryptedPreferencesSerializer]); dosya korunur.
     */
    fun create(
        produceFile: () -> File,
        aeadSupplier: AeadSupplier,
        legacyFile: File? = null,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ): DataStoreSessionStore = DataStoreSessionStore(
        createDataStore(
            produceFile = produceFile,
            aeadSupplier = aeadSupplier,
            legacyFile = legacyFile,
            scope = scope,
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ),
    )

    /** Testler bozulma işleyicisi olmadan da kurabilsin diye ayrı (gerçek bozulmayı gözlemlemek için). */
    fun createDataStore(
        produceFile: () -> File,
        aeadSupplier: AeadSupplier,
        legacyFile: File? = null,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        corruptionHandler: ReplaceFileCorruptionHandler<Preferences>? = null,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        // FileStorage: DataStore'un standart atomik dosya yazımı (geçici dosya + yeniden adlandırma),
        // yalnızca seri hale getirici şifreli olanla değiştirildi.
        storage = FileStorage(
            serializer = EncryptedPreferencesSerializer(aeadSupplier),
            produceFile = produceFile,
        ),
        corruptionHandler = corruptionHandler,
        migrations = listOfNotNull(legacyFile?.let { LegacyPlaintextSessionMigration(it) }),
        scope = scope,
    )
}
