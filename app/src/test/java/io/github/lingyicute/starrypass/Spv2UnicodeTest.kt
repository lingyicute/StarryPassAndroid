package io.github.lingyicute.starrypass

import io.github.lingyicute.starrypass.crypto.DeriveRequest
import io.github.lingyicute.starrypass.crypto.Spv2Engine
import io.github.lingyicute.starrypass.crypto.Spv2Spec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Spv2UnicodeTest {
    @Test
    fun `unpaired surrogates use TextEncoder replacement while valid pairs stay intact`() {
        val input = "A\uD800\uD800\uDC00\uDFFFZ"
        val expected = "A\uFFFD\uD800\uDC00\uFFFDZ".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, Spv2Spec.normalizeMasterToUtf8(input))
        assertArrayEquals(expected, Spv2Spec.normalizeCodeToUtf8("\uFEFF $input \u00A0"))
        assertArrayEquals("\uFFFD".toByteArray(Charsets.UTF_8), Spv2Spec.normalizeMasterToUtf8("\uDC00"))
    }

    @Test
    fun `unpaired surrogate passwords match the upstream web bundle`() = runBlocking {
        // Generated with StarryPass web bundle at 8d5dd9306fa70db278a636547667dbf86bd8c066.
        val engine = Spv2Engine()
        assertEquals("_#9HG-*r5v&1!H!w", engine.derive(DeriveRequest("\uD800", "site")).password)
        assertEquals("u?U0n8F#D5E3F?lk", engine.derive(DeriveRequest("valid", "\uDC00")).password)
        assertEquals(
            "yep+levQpU8NW^XF",
            engine.derive(DeriveRequest("A\uD800\uD800\uDC00\uDFFFZ", " \uD800  ")).password
        )
    }
}
