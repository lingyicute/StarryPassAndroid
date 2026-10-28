/*
 * StarryPass Android — Audited Cryptographic Primitives Wrapper
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 *
 * ZERO hand-written cryptographic primitives:
 *   - SHA-256 .......... Platform JCA MessageDigest ("SHA-256", BoringSSL/Conscrypt on Android)
 *   - HMAC-SHA-256 ..... Platform JCA Mac ("HmacSHA256", BoringSSL/Conscrypt on Android)
 *   - Constant-time == . Platform JCA MessageDigest.isEqual
 *   - CSPRNG ........... Platform JCA SecureRandom (BoringSSL / Linux getrandom)
 *   - HKDF-SHA-256 ..... Bouncy Castle HKDFBytesGenerator (RFC 5869, NCC Group audited)
 *   - Argon2id v1.3 .... Bouncy Castle Argon2BytesGenerator (RFC 9106, NCC Group audited)
 */
package io.github.lingyicute.starrypass.crypto

import org.bouncycastle.crypto.DataLengthException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object Spv2Primitives {

    private val secureRandom = SecureRandom()

    fun randomBytes(length: Int): ByteArray {
        val out = ByteArray(length)
        secureRandom.nextBytes(out)
        return out
    }

    fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }

    fun hmacSha256(keyBytes: ByteArray, dataBytes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(keyBytes, "HmacSHA256")
        mac.init(keySpec)
        val out = mac.doFinal(dataBytes)
        try {
            keySpec.destroy()
        } catch (_: Throwable) {
            // SecretKeySpec.destroy() is optional on older Android API levels.
        }
        return out
    }

    fun constantTimeEquals(a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null) return false
        return MessageDigest.isEqual(a, b)
    }

    /**
     * Argon2id v1.3 (RFC 9106) via Bouncy Castle's audited [Argon2BytesGenerator].
     * Supports both `secret` (RFC 9106 `K`, used in §5.3 KAT) and `ad` (RFC 9106 `X`,
     * used for StarryPass domain separation `"StarryPass-v2:argon2id-ad:v1"`).
     */
    fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        ad: ByteArray?,
        secret: ByteArray? = null,
        memoryKiB: Int,
        passes: Int,
        parallelism: Int,
        tagLength: Int = Spv2Spec.SESSION_KEY_BYTES
    ): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKiB)
            .withIterations(passes)
            .withParallelism(parallelism)

        if (ad != null && ad.isNotEmpty()) {
            builder.withAdditional(ad)
        }
        if (secret != null && secret.isNotEmpty()) {
            builder.withSecret(secret)
        }

        val params = builder.build()
        try {
            val out = ByteArray(tagLength)
            val generator = Argon2BytesGenerator()
            generator.init(params)
            generator.generateBytes(password, out, 0, tagLength)
            return out
        } catch (oom: OutOfMemoryError) {
            throw Spv2Exception(Spv2ErrorCode.OUT_OF_MEMORY, "OUT_OF_MEMORY", oom)
        } finally {
            builder.clear()
            params.clear()
        }
    }

    /**
     * Stateful RFC 5869 HKDF-SHA-256 byte stream built directly on Bouncy Castle's
     * [HKDFBytesGenerator] (no custom HMAC expand loop).
     */
    class HkdfStream(
        sk: ByteArray,
        salt: ByteArray,
        info: ByteArray
    ) {
        private val hkdf = HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(sk, salt, info))
        }
        private var blockIdx = 0
        private val blockBuf = ByteArray(Spv2Spec.HKDF_BLOCK_BYTES)
        private var pos = Spv2Spec.HKDF_BLOCK_BYTES // trigger first block load on first read

        fun nextUnsignedByte(): Int {
            if (pos >= blockBuf.size) {
                blockIdx++
                if (blockIdx > Spv2Spec.HKDF_MAX_BLOCKS) {
                    throw Spv2Exception(Spv2ErrorCode.HKDF_EXHAUSTED)
                }
                try {
                    hkdf.generateBytes(blockBuf, 0, Spv2Spec.HKDF_BLOCK_BYTES)
                } catch (e: DataLengthException) {
                    throw Spv2Exception(Spv2ErrorCode.HKDF_EXHAUSTED, cause = e)
                }
                pos = 0
            }
            return blockBuf[pos++].toInt() and 0xFF
        }

        /**
         * Zero-bias rejection sampling over `[0, n)`, byte-identical to upstream `uniformInt(n)`.
         */
        fun uniformInt(n: Int): Int {
            require(n in 1..256) { "Pool size out of byte range: $n" }
            val limit = 256 - (256 % n)
            while (true) {
                val b = nextUnsignedByte()
                if (b < limit) return b % n
            }
        }

        fun dispose() {
            Spv2Spec.zeroize(blockBuf)
            pos = blockBuf.size
        }
    }
}
