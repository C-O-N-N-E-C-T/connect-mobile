package com.connect.mobile.data.supabase

import com.connect.mobile.auth.AuthError
import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseErrorMapperTest {
    @Test fun mapsGoTrueCodes() {
        assertEquals(AuthError.BadCredentials, SupabaseErrorMapper.fromRest("invalid_credentials", 400))
        assertEquals(AuthError.BadCredentials, SupabaseErrorMapper.fromRest("validation_failed", 400))
        assertEquals(AuthError.AccountDisabled, SupabaseErrorMapper.fromRest("user_banned", 400))
        assertEquals(AuthError.AccountNotReady, SupabaseErrorMapper.fromRest("email_not_confirmed", 400))
        assertEquals(AuthError.RateLimited, SupabaseErrorMapper.fromRest("over_request_rate_limit", 429))
        assertEquals(AuthError.RateLimited, SupabaseErrorMapper.fromRest(null, 429))
        assertEquals(AuthError.Server, SupabaseErrorMapper.fromRest(null, 500))
    }
}
