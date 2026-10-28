package io.github.lingyicute.starrypass

import com.google.crypto.tink.subtle.Hkdf
import io.github.lingyicute.starrypass.crypto.DeriveRequest
import io.github.lingyicute.starrypass.crypto.Spv2Engine
import io.github.lingyicute.starrypass.crypto.Spv2ErrorCode
import io.github.lingyicute.starrypass.crypto.Spv2Exception
import io.github.lingyicute.starrypass.crypto.Spv2Primitives
import io.github.lingyicute.starrypass.crypto.Spv2Spec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Verifies 100% bit-level compatibility with upstream StarryPass v2 / v2.1:
 *   1. Power-on KAT self-test (RFC 9106 §5.3 Argon2id + RFC 5869 TC1 HKDF)
 *   2. Cross-library verification: Bouncy Castle HKDFStream vs Google Tink Hkdf
 *   3. All 7 original vectors (`vectors.json`)
 *   4. All 44 upstream golden cases (`golden.json` — NFC/NFD, emoji, RTL, fullwidth, clamping, paranoid, errors)
 *   5. Single-slot session cache reuse & `wipeSession()` invalidation
 */
class Spv2CompatibilityTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun readResource(name: String): String {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Missing test resource: $name"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun parseLooseInt(el: JsonElement?): Int? {
        if (el == null || el is JsonNull) return null
        val prim = el as? JsonPrimitive ?: return null
        return Spv2Spec.parseJsInt(prim.content)
    }

    @Test
    fun `power-on self test passes RFC 9106 and RFC 5869 KATs`() = runBlocking {
        val engine = Spv2Engine()
        assertTrue(engine.runSelfTest())
        assertTrue(engine.isSelfTestVerified)
    }

    @Test
    fun `BouncyCastle HkdfStream matches Google Tink RFC 5869 Hkdf byte-for-byte across multiple blocks`() {
        val ikm = ByteArray(32) { (it * 7 + 3).toByte() }
        val salt = "StarryPass-v2:hkdf-extract:v1".toByteArray(Charsets.US_ASCII)
        val info = "StarryPass-v2:hkdf-expand:v1\u0000test-info-payload".toByteArray(Charsets.US_ASCII)

        val stream = Spv2Primitives.HkdfStream(sk = ikm, salt = salt, info = info)
        val fromBc = ByteArray(200) { stream.nextUnsignedByte().toByte() }
        stream.dispose()

        val fromTink = Hkdf.computeHkdf("HMACSHA256", ikm, salt, info, 200)
        assertArrayEquals(fromTink, fromBc)
    }

    @Test
    fun `all 7 original StarryPass v2 vectors match byte-for-byte and session cache works`() = runBlocking {
        val arr = json.parseToJsonElement(readResource("vectors.json")).jsonArray
        assertEquals(7, arr.size)

        val engine = Spv2Engine()
        for ((idx, item) in arr.withIndex()) {
            val obj = item.jsonObject
            val args = obj["args"]!!.jsonArray
            val expectedPwd = obj["pwd"]!!.jsonPrimitive.content

            val req = DeriveRequest.fromRaw(
                master = args[0].jsonPrimitive.content,
                code = args[1].jsonPrimitive.content,
                tier = args[2].jsonPrimitive.content,
                counter = parseLooseInt(args[3]),
                length = parseLooseInt(args[4]),
                mode = args[5].jsonPrimitive.content
            )
            val res = engine.derive(req)
            assertEquals("Vector #${idx + 1} mismatch", expectedPwd, res.password)
        }

        // Re-deriving the last vector (paranoid tier!) must hit the single-slot session cache in < 20ms
        val lastObj = arr.last().jsonObject
        val lastArgs = lastObj["args"]!!.jsonArray
        val lastExpected = lastObj["pwd"]!!.jsonPrimitive.content
        val cachedRes = engine.derive(
            DeriveRequest.fromRaw(
                master = lastArgs[0].jsonPrimitive.content,
                code = lastArgs[1].jsonPrimitive.content,
                tier = lastArgs[2].jsonPrimitive.content,
                counter = parseLooseInt(lastArgs[3]),
                length = parseLooseInt(lastArgs[4]),
                mode = lastArgs[5].jsonPrimitive.content
            )
        )
        assertEquals(lastExpected, cachedRes.password)
        assertTrue("Expected cached=true on repeat derive", cachedRes.cached)

        // Changing counter/length/mode with same (master, code, tier) must ALSO hit session cache
        val rotatedRes = engine.derive(
            DeriveRequest.fromRaw(
                master = lastArgs[0].jsonPrimitive.content,
                code = lastArgs[1].jsonPrimitive.content,
                tier = lastArgs[2].jsonPrimitive.content,
                counter = 2,
                length = 20,
                mode = "alnum"
            )
        )
        assertTrue("Expected cached=true when rotating counter/length/mode", rotatedRes.cached)
        assertEquals(20, rotatedRes.password.length)

        // After wipeSession(), cached must be false again
        engine.wipeSession()
        val afterWipe = engine.derive(
            DeriveRequest.fromRaw(
                master = arr[0].jsonObject["args"]!!.jsonArray[0].jsonPrimitive.content,
                code = arr[0].jsonObject["args"]!!.jsonArray[1].jsonPrimitive.content,
                tier = "standard",
                counter = 1,
                length = 16,
                mode = "full"
            )
        )
        assertFalse("Expected cached=false after wipeSession()", afterWipe.cached)
    }

    @Test
    fun `all 44 upstream golden cases match byte-for-byte`() = runBlocking {
        val root = json.parseToJsonElement(readResource("golden.json")).jsonObject
        val cases = root["cases"]!!.jsonArray
        assertEquals(44, cases.size)

        val engine = Spv2Engine()
        for ((idx, item) in cases.withIndex()) {
            val caseObj = item.jsonObject
            val args = caseObj["args"]!!.jsonObject
            val expectedError = caseObj["error"]?.jsonPrimitive?.content

            val req = DeriveRequest.fromRaw(
                master = (args["master"] as? JsonPrimitive)?.content,
                code = (args["code"] as? JsonPrimitive)?.content,
                tier = (args["tier"] as? JsonPrimitive)?.content,
                counter = parseLooseInt(args["counter"]),
                length = parseLooseInt(args["length"]),
                mode = (args["mode"] as? JsonPrimitive)?.content
            )

            if (expectedError != null) {
                try {
                    engine.derive(req)
                    fail("Golden case #${idx + 1} expected error $expectedError")
                } catch (e: Spv2Exception) {
                    assertEquals("Golden case #${idx + 1} error code", expectedError, e.code.name)
                }
            } else {
                val expectedPwd = caseObj["pwd"]!!.jsonPrimitive.content
                val res = engine.derive(req)
                assertEquals("Golden case #${idx + 1} mismatch ($args)", expectedPwd, res.password)
            }
        }

        // Extra sanity check on explicit empty inputs
        try {
            engine.derive(DeriveRequest(master = "", code = "github"))
            fail("Expected EMPTY_INPUT")
        } catch (e: Spv2Exception) {
            assertEquals(Spv2ErrorCode.EMPTY_INPUT, e.code)
        }
    }
}
