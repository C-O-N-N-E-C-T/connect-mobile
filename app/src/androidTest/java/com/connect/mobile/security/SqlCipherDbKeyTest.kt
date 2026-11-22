package com.connect.mobile.security

import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.connect.mobile.data.crypto.AndroidKeystoreMasterKey
import com.connect.mobile.data.db.DbKeyProvider
import com.google.crypto.tink.integration.android.AndroidKeystore
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * OSP-75 kabul kriteri: DbKeyProvider parolasıyla açılan SQLCipher DB'sinin dosya başlığı şifreli
 * (ilk 16 bayt "SQLite format 3\u0000" DEĞİL) ve aynı parolayla (yeni sağlayıcı örneği = yeniden
 * başlatma) tekrar açılabiliyor. SQLCipher yalnızca androidTest bağımlılığıdır (Room/E8 öncesi).
 *
 * Uygulamanın gerçek anahtar dosyasına ve alias'ına dokunmaz.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherDbKeyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "connect_test_db_${UUID.randomUUID()}"
    private val dbName = "osp75-test-${UUID.randomUUID()}.db"
    private val keyFile = File(context.cacheDir, "osp75-${UUID.randomUUID()}.key")

    @Before fun loadNativeLibrary() {
        System.loadLibrary("sqlcipher")
    }

    @After fun cleanUp() {
        context.deleteDatabase(dbName)
        keyFile.delete()
        if (AndroidKeystore.hasKey(alias)) AndroidKeystore.deleteKey(alias)
    }

    private fun provider() = DbKeyProvider(keyFile, AndroidKeystoreMasterKey(alias), encryptedDataExists = { false })

    private fun open(passphrase: ByteArray): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE victim (id INTEGER PRIMARY KEY, note TEXT NOT NULL)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        // Fabrikanın parolayı kendi içinde sıfırlama ihtimaline karşı kopya verilir.
        return SupportOpenHelperFactory(passphrase.copyOf()).create(config)
    }

    @Test fun databaseHeaderIsEncryptedAndReopensWithSameKey() = runBlocking {
        val pass = provider().sqlCipherPassphrase()
        open(pass).use { helper ->
            helper.writableDatabase.execSQL("INSERT INTO victim (id, note) VALUES (1, 'unsynced-victim-PLAIN')")
        }

        val bytes = context.getDatabasePath(dbName).readBytes()
        val header = bytes.copyOf(16)
        assertNotEquals("SQLite format 3\u0000", String(header, Charsets.US_ASCII))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("unsynced-victim-PLAIN"))

        // Yeni sağlayıcı örneği = süreç yeniden başlamış gibi; aynı sarılmış dosyadan aynı parola.
        val again = provider().sqlCipherPassphrase()
        assertArrayEquals(pass, again)
        open(again).use { helper ->
            helper.readableDatabase.query("SELECT note FROM victim WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals("unsynced-victim-PLAIN", c.getString(0))
            }
        }
        pass.fill(0)
        again.fill(0)
    }

    @Test fun wrongKeyCannotOpen() = runBlocking {
        open(provider().sqlCipherPassphrase()).use { it.writableDatabase.execSQL("INSERT INTO victim VALUES (1, 'x')") }
        val wrong = DbKeyProvider.toSqlCipherRawKey(ByteArray(32) { 7 })
        try {
            open(wrong).use { it.readableDatabase.query("SELECT count(*) FROM victim").use { c -> c.moveToFirst() } }
            fail("database opened with a wrong key")
        } catch (e: SQLiteException) {
            // beklenen: SQLCipher'ın SQLiteNotADatabaseException'ı ("file is not a database") bunun alt türü.
        }
    }

    private inline fun <T> SupportSQLiteOpenHelper.use(block: (SupportSQLiteOpenHelper) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }
}
