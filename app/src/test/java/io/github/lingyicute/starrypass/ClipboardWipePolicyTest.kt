package io.github.lingyicute.starrypass

import io.github.lingyicute.starrypass.crypto.Spv2Primitives
import io.github.lingyicute.starrypass.security.ClipboardWipePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardWipePolicyTest {
    private val expected = Spv2Primitives.sha256("secret".toByteArray(Charsets.UTF_8))

    @Test
    fun `wipes when the clipboard still holds the copied password`() {
        assertTrue(ClipboardWipePolicy.shouldWipe(expected, true, "secret"))
    }

    @Test
    fun `keeps a readable clip that the user replaced`() {
        assertFalse(ClipboardWipePolicy.shouldWipe(expected, true, "other"))
        assertFalse(ClipboardWipePolicy.shouldWipe(expected, true, null))
    }

    @Test
    fun `wipes when a background app cannot read the clipboard`() {
        assertTrue(ClipboardWipePolicy.shouldWipe(expected, false, null))
    }

    @Test
    fun `keeps an unreadable clip that the user is known to have replaced`() {
        assertFalse(ClipboardWipePolicy.shouldWipe(expected, false, null, replacedByOther = true))
    }

    @Test
    fun `duplicate system callback while password is still readable does not mark clip as replaced`() {
        assertFalse(ClipboardWipePolicy.isReplacedByOther(expected, true, "secret"))
        assertTrue(ClipboardWipePolicy.isReplacedByOther(expected, true, "other"))
        assertTrue(ClipboardWipePolicy.isReplacedByOther(expected, true, null))
        assertTrue(ClipboardWipePolicy.isReplacedByOther(expected, false, null))
    }
}
