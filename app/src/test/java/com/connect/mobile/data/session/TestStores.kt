package com.connect.mobile.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import java.io.File

/** One DataStore instance per file at a time: callers must [close] before reopening the same file. */
class TestStoreHandle(val file: File) {
    private val job = Job()
    val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + job),
        produceFile = { file },
    )
    val store = DataStoreSessionStore(dataStore)

    suspend fun close() = job.cancelAndJoin()
}
