package com.connect.mobile.data.session

import com.connect.mobile.data.crypto.TestCrypto
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * OSP-75: [DataStoreSessionStoreTest]'teki tüm SessionStore sözleşme testleri (clearAuth / clearGrant
 * device_id'yi korur, dosyayı silmez, bozuk dosya çökertmez…) şifreli depo üzerinde tekrar çalışır.
 */
class EncryptedSessionStoreContractTest : DataStoreSessionStoreTest() {
    // Aynı dosya kapatılıp yeniden açıldığında aynı anahtar gerekir: test başına tek AEAD.
    private val aead = TestCrypto.softwareAead()

    override fun handle(file: File) = TestStoreHandle(file, aead = { aead })

    override fun createWithCorruptionHandler(file: File, scope: CoroutineScope): SessionStore =
        EncryptedSessionStore.create(produceFile = { file }, aeadSupplier = { aead }, scope = scope)
}
