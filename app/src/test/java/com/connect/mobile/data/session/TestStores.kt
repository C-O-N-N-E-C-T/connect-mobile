package com.connect.mobile.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import java.io.File

/**
 * One DataStore instance per file at a time: callers must [close] before reopening the same file.
 *
 * OSP-75: [aead] verilirse aynı mantık şifreli DataStore üzerinde kurulur (sözleşme testleri iki
 * uygulamaya da uygulanır); [legacyFile] eski düz metin dosyanın taşınmasını açar.
 */
class TestStoreHandle(val file: File, aead: AeadSupplier? = null, legacyFile: File? = null) {
    private val job = Job()
    val dataStore: DataStore<Preferences> = if (aead == null) {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
    } else {
        EncryptedSessionStore.createDataStore(
            produceFile = { file },
            aeadSupplier = aead,
            legacyFile = legacyFile,
            scope = CoroutineScope(Dispatchers.IO + job),
        )
    }
    val store = DataStoreSessionStore(dataStore)

    suspend fun close() = job.cancelAndJoin()
}
