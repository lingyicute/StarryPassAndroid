package io.github.lingyicute.starrypass

import android.security.keystore.KeyProperties
import io.github.lingyicute.starrypass.security.KeystoreHardwarePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreHardwarePolicyTest {
    @Test
    fun `android 12 and later require TEE or StrongBox`() {
        assertTrue(KeystoreHardwarePolicy.isHardwareBacked(31, KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, null))
        assertTrue(KeystoreHardwarePolicy.isHardwareBacked(35, KeyProperties.SECURITY_LEVEL_STRONGBOX, null))
        assertFalse(KeystoreHardwarePolicy.isHardwareBacked(31, KeyProperties.SECURITY_LEVEL_SOFTWARE, true))
        assertFalse(KeystoreHardwarePolicy.isHardwareBacked(31, KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE, true))
        assertFalse(KeystoreHardwarePolicy.isHardwareBacked(31, null, true))
    }

    @Test
    fun `android 8 through 11 require positive secure hardware evidence`() {
        assertTrue(KeystoreHardwarePolicy.isHardwareBacked(26, null, true))
        assertTrue(KeystoreHardwarePolicy.isHardwareBacked(30, null, true))
        assertFalse(KeystoreHardwarePolicy.isHardwareBacked(30, null, false))
        assertFalse(KeystoreHardwarePolicy.isHardwareBacked(30, null, null))
    }
}
