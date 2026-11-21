package com.connect.mobile.ui.common

import com.connect.mobile.auth.AuthError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthErrorTextTest {
    @Test fun everyErrorHasItsOwnString() {
        val ids = AuthError.all.map { it.messageRes() }
        assertTrue(ids.all { it != 0 })
        assertEquals(AuthError.all.size, ids.toSet().size)
        assertEquals(13, AuthError.all.size)
    }
}
