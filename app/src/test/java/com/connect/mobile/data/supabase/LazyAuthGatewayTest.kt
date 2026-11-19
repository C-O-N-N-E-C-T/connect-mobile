package com.connect.mobile.data.supabase

import com.connect.mobile.auth.Events
import com.connect.mobile.auth.FakeGateway
import com.connect.mobile.auth.InMemorySessionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LazyAuthGatewayTest {
    /** R8: building the gateway (and the SupabaseClient) waits for the first real use. */
    @Test fun createsTheDelegateOnFirstUseOnly() = runTest {
        var created = 0
        val events = Events()
        val gateway = LazyAuthGateway { created++; FakeGateway(events) }
        assertEquals(0, created)
        gateway.clearLocalSession()
        gateway.accessTokenOrNull()
        assertEquals(1, created)
        assertEquals(listOf("clearLocalSession"), events)
    }

    /** R11: supabase-kt rejects a SUPABASE_URL with an API path with IllegalStateException. */
    @Test fun invalidSupabaseUrlGivesNullInsteadOfCrashing() {
        val gateway = SupabaseKtAuthGateway.createOrNull(
            supabaseUrl = "https://example.supabase.co/auth/v1",
            supabaseAnonKey = "test-anon-key",
            store = InMemorySessionStore(),
            enableLifecycleCallbacks = false,
        )
        assertNull(gateway)
        // Control: the same call with a plain project URL works in a JVM unit test.
        assertNotNull(
            SupabaseKtAuthGateway.createOrNull(
                supabaseUrl = "https://example.supabase.co",
                supabaseAnonKey = "test-anon-key",
                store = InMemorySessionStore(),
                enableLifecycleCallbacks = false,
            ),
        )
    }
}
