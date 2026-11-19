package com.connect.mobile

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.connect.mobile.data.network.HttpClientFactory
import com.connect.mobile.util.causeChainMessages
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OS-level proof of NFR-SEC-02 (cleartextTrafficPermitted=false). Run on a phone or emulator:
 * ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class CleartextPolicyTest {

    @Test
    fun platformPolicyForbidsCleartext() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted)
        assertFalse(policy.isCleartextTrafficPermitted("10.0.2.2"))
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
    }

    @Test
    fun appHttpClientRefusesHttpBeforeAnySocketIsOpened() = runBlocking {
        val client = HttpClientFactory.create()
        val error = runCatching { client.get("http://example.com/") }.exceptionOrNull()
        client.close()
        assertNotNull("plain http request unexpectedly succeeded", error)
        // OkHttp: UnknownServiceException "CLEARTEXT communication to example.com not permitted by
        // network security policy". Ktor may wrap it, so check the whole cause chain.
        val messages = error!!.causeChainMessages()
        assertTrue(messages, messages.contains("CLEARTEXT", ignoreCase = true))
        assertTrue(messages, messages.contains("not permitted"))
    }
}
