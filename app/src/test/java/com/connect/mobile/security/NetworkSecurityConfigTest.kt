package com.connect.mobile.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Static guard for NFR-SEC-02. The OS-level proof is androidTest/CleartextPolicyTest. */
class NetworkSecurityConfigTest {
    private val config = File("src/main/res/xml/network_security_config.xml").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test fun baseConfigForbidsCleartextWithoutExceptions() {
        assertTrue(Regex("""<base-config\s+cleartextTrafficPermitted="false"""").containsMatchIn(config))
        assertFalse(config.contains("<domain-config"))
        assertFalse(config.contains("<debug-overrides"))
        assertFalse(config.contains("cleartextTrafficPermitted=\"true\""))
        // No custom trust anchors (e.g. user-installed CAs): the platform default applies.
        assertFalse(config.contains("trust-anchors"))
        assertFalse(config.contains("src=\"user\""))
    }

    @Test fun manifestUsesTheConfig() {
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertFalse(manifest.contains("usesCleartextTraffic=\"true\""))
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
    }

    @Test fun noOtherSourceSetOverridesTheConfig() {
        val overrides = File("src").walkTopDown()
            .filter { it.name == "network_security_config.xml" && !it.path.contains("src/main/") }
            .toList()
        assertTrue(overrides.toString(), overrides.isEmpty())
    }
}
