package com.connect.mobile.config

import com.connect.mobile.BuildConfig

/**
 * Values from local.properties via BuildConfig. Only non-blank is checked: there is deliberately NO
 * https:// scheme check, so an http:// URL reaches the OS and is refused by the Network Security
 * Config (that refusal is what NFR-SEC-02 asks to demonstrate).
 */
data class AppConfig(
    val supabaseUrl: String,
    val supabaseAnonKey: String,
    val apiBaseUrl: String,
    val useFakeBackend: Boolean,
) {
    val isSupabaseConfigured: Boolean get() = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()

    /** With the debug fake backend, API_BASE_URL is not needed. */
    val isConfigured: Boolean get() = isSupabaseConfigured && (useFakeBackend || apiBaseUrl.isNotBlank())

    companion object {
        fun fromBuildConfig() = AppConfig(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
            apiBaseUrl = BuildConfig.API_BASE_URL,
            useFakeBackend = BuildConfig.USE_FAKE_BACKEND,
        )
    }
}
