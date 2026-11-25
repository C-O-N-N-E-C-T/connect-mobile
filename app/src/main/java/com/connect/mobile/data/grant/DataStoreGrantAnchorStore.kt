package com.connect.mobile.data.grant

import androidx.datastore.core.DataStore
import androidx.datastore.core.FileStorage
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.connect.mobile.auth.grant.GrantAnchor
import com.connect.mobile.auth.grant.GrantAnchorStore
import com.connect.mobile.data.session.AeadSupplier
import com.connect.mobile.data.session.EncryptedPreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Grant çapası için AYRI şifreli DataStore (OSP-73). OSP-75'in şifreli seri hale getiricisi yeniden
 * kullanılır; farklı associated data sayesinde oturum dosyasıyla yer değiştirilemez. Root'lu bir
 * cihazda çapa elle değiştirilip süre uzatılamaz. Bozuk dosya boş sayılır: çapa duvar saatinden
 * yeniden kurulur (veri kaybı yok).
 */
class DataStoreGrantAnchorStore(private val dataStore: DataStore<Preferences>) : GrantAnchorStore {

    override suspend fun read(): GrantAnchor? {
        val p = dataStore.data.first()
        return GrantAnchor(
            grantKey = p[GRANT_KEY] ?: return null,
            bootCount = p[BOOT_COUNT] ?: return null,
            elapsedAtAnchorMillis = p[ELAPSED_AT_ANCHOR] ?: return null,
            consumedAtAnchorMillis = p[CONSUMED_AT_ANCHOR] ?: return null,
            lastSeenWallMillis = p[LAST_SEEN_WALL] ?: return null,
        )
    }

    override suspend fun write(anchor: GrantAnchor) {
        dataStore.edit {
            it[GRANT_KEY] = anchor.grantKey
            it[BOOT_COUNT] = anchor.bootCount
            it[ELAPSED_AT_ANCHOR] = anchor.elapsedAtAnchorMillis
            it[CONSUMED_AT_ANCHOR] = anchor.consumedAtAnchorMillis
            it[LAST_SEEN_WALL] = anchor.lastSeenWallMillis
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    companion object {
        /** -> files/datastore/connect_grant_anchor.enc */
        const val FILE_NAME = "connect_grant_anchor.enc"
        val ASSOCIATED_DATA: ByteArray = "connect_grant_anchor.v1".toByteArray(Charsets.UTF_8)

        private val GRANT_KEY = stringPreferencesKey("anchor_grant_key")
        private val BOOT_COUNT = intPreferencesKey("anchor_boot_count")
        private val ELAPSED_AT_ANCHOR = longPreferencesKey("anchor_elapsed_ms")
        private val CONSUMED_AT_ANCHOR = longPreferencesKey("anchor_consumed_ms")
        private val LAST_SEEN_WALL = longPreferencesKey("anchor_last_seen_wall_ms")

        fun create(
            produceFile: () -> File,
            aeadSupplier: AeadSupplier,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ): DataStoreGrantAnchorStore = DataStoreGrantAnchorStore(
            PreferenceDataStoreFactory.create(
                storage = FileStorage(
                    serializer = EncryptedPreferencesSerializer(aeadSupplier, ASSOCIATED_DATA),
                    produceFile = produceFile,
                ),
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
            ),
        )
    }
}
