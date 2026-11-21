package com.connect.mobile.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {
    @Test fun fakeBackendNeedsOnlySupabaseKeys() {
        assertTrue(AppConfig("https://x.supabase.co", "anon", "", useFakeBackend = true).isConfigured)
        assertFalse(AppConfig("https://x.supabase.co", "anon", "", useFakeBackend = false).isConfigured)
        assertTrue(AppConfig("https://x.supabase.co", "anon", "https://api", useFakeBackend = false).isConfigured)
        assertFalse(AppConfig("", "anon", "https://api", useFakeBackend = true).isConfigured)
    }

    @Test fun noSchemeCheckSoHttpReachesTheOs() {
        // Deliberate: cleartext is refused by the Network Security Config, not by AppConfig.
        assertTrue(AppConfig("https://x.supabase.co", "anon", "http://10.0.2.2:3000", false).isConfigured)
    }
}
