/*
 * StarryPass Android — Deterministic Password Generator
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.crypto

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * StarryPass v2 / v2.1 specification constants and canonicalisation rules.
 *
 * Every byte constant, pool order, ID, and boundary clamp matches upstream
 * `src/spv2-core.mjs` verbatim to guarantee 100% bit-identical password output.
 */
object Spv2Spec {

    const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val DIGIT = "0123456789"
    const val SYMBOL = "!@#$%^&*()-_=+?"

    const val HKDF_MAX_BLOCKS = 255
    const val HKDF_BLOCK_BYTES = 32
    const val SESSION_KEY_BYTES = 32
    const val SESSION_NONCE_BYTES = 32
    const val REJECTION_MAX_ATTEMPTS = 128

    const val MIN_COUNTER = 1
    const val MAX_COUNTER = 9999
    const val DEFAULT_COUNTER = 1

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 64
    const val DEFAULT_LENGTH = 16

    /*
     * Domain-separation constants — byte-for-byte identical to StarryPass v2.
     * Note carefully which constants include a trailing NUL (\u0000) byte!
     */
    internal fun domainSalt(): ByteArray = "StarryPass-v2:salt\u0000".toByteArray(Charsets.US_ASCII)
    internal fun domainAd(): ByteArray = "StarryPass-v2:argon2id-ad:v1".toByteArray(Charsets.US_ASCII)
    internal fun domainExtract(): ByteArray = "StarryPass-v2:hkdf-extract:v1".toByteArray(Charsets.US_ASCII)
    internal fun domainExpand(): ByteArray = "StarryPass-v2:hkdf-expand:v1\u0000".toByteArray(Charsets.US_ASCII)
    internal fun domainCache(): ByteArray = "StarryPass-v2:session-cache-tag:v1\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * ECMA-262 `String.prototype.trim()` whitespace definition (WhiteSpace + LineTerminator),
     * including U+FEFF (BOM), U+00A0 (NBSP), and U+3000 (Ideographic Space).
     */
    fun ecmaTrim(input: String): String {
        var start = 0
        var end = input.length
        while (start < end && isEcmaWhitespace(input[start])) start++
        while (end > start && isEcmaWhitespace(input[end - 1])) end--
        return if (start == 0 && end == input.length) input else input.substring(start, end)
    }

    private fun isEcmaWhitespace(ch: Char): Boolean = when (ch.code) {
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x00A0,
        0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
        in 0x2000..0x200A -> true
        else -> false
    }

    /**
     * Matches JS `Math.max(1, Math.min(9999, parseInt(counter, 10) || 1))`.
     */
    fun clampCounter(raw: Int?): Int {
        val v = if (raw == null || raw == 0) DEFAULT_COUNTER else raw
        return max(MIN_COUNTER, min(MAX_COUNTER, v))
    }

    /**
     * Matches JS `Math.max(4, Math.min(64, parseInt(length, 10) || 16))`.
     */
    fun clampLength(raw: Int?): Int {
        val v = if (raw == null || raw == 0) DEFAULT_LENGTH else raw
        return max(MIN_LENGTH, min(MAX_LENGTH, v))
    }

    /**
     * Parses arbitrary string/number input identically to JS `parseInt(x, 10)`.
     */
    fun parseJsInt(raw: String?): Int? {
        if (raw == null) return null
        val s = ecmaTrim(raw)
        if (s.isEmpty()) return null
        var i = 0
        var negative = false
        if (s[0] == '+' || s[0] == '-') {
            negative = s[0] == '-'
            i++
        }
        val startDigits = i
        var acc = 0L
        while (i < s.length && s[i] in '0'..'9') {
            acc = (acc * 10L + (s[i] - '0')).coerceAtMost(Int.MAX_VALUE.toLong() + 1L)
            i++
        }
        if (i == startDigits) return null
        val signed = if (negative) -acc else acc
        return signed.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    fun normalizeMasterToUtf8(master: String): ByteArray {
        // Master password is NEVER trimmed — only NFC-normalised!
        val nfc = Normalizer.normalize(master, Normalizer.Form.NFC)
        return nfc.toByteArray(Charsets.UTF_8)
    }

    fun normalizeCodeToUtf8(code: String): ByteArray {
        // Site code IS trimmed before NFC normalisation.
        val trimmed = ecmaTrim(code)
        val nfc = Normalizer.normalize(trimmed, Normalizer.Form.NFC)
        return nfc.toByteArray(Charsets.UTF_8)
    }

    internal fun writeLE32(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    internal fun writeLE16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    internal fun concatBytes(vararg arrays: ByteArray): ByteArray {
        var total = 0
        for (a in arrays) total += a.size
        val out = ByteArray(total)
        var pos = 0
        for (a in arrays) {
            System.arraycopy(a, 0, out, pos, a.size)
            pos += a.size
        }
        return out
    }

    internal fun lenPrefixed(bytes: ByteArray): ByteArray {
        val out = ByteArray(4 + bytes.size)
        writeLE32(out, 0, bytes.size)
        System.arraycopy(bytes, 0, out, 4, bytes.size)
        return out
    }

    fun zeroize(buf: ByteArray?) {
        buf?.fill(0)
    }

    fun zeroize(buf: CharArray?) {
        buf?.fill('\u0000')
    }
}

enum class Spv2Mode(
    val key: String,
    val id: Byte,
    val pool: String,
    val requiredClasses: List<String>
) {
    FULL(
        key = "full",
        id = 1,
        pool = Spv2Spec.LOWER + Spv2Spec.UPPER + Spv2Spec.DIGIT + Spv2Spec.SYMBOL,
        requiredClasses = listOf(Spv2Spec.LOWER, Spv2Spec.UPPER, Spv2Spec.DIGIT, Spv2Spec.SYMBOL)
    ),
    ALNUM(
        key = "alnum",
        id = 2,
        pool = Spv2Spec.LOWER + Spv2Spec.UPPER + Spv2Spec.DIGIT,
        requiredClasses = listOf(Spv2Spec.LOWER, Spv2Spec.UPPER, Spv2Spec.DIGIT)
    ),
    PIN(
        key = "pin",
        id = 3,
        pool = Spv2Spec.DIGIT,
        requiredClasses = listOf(Spv2Spec.DIGIT)
    );

    companion object {
        fun fromKey(key: String?): Spv2Mode = entries.firstOrNull { it.key == key } ?: FULL
    }
}

enum class Spv2Tier(
    val key: String,
    val id: Byte,
    val memoryKiB: Int,
    val passes: Int,
    val parallelism: Int
) {
    STANDARD(key = "standard", id = 1, memoryKiB = 65_536, passes = 3, parallelism = 4),
    PARANOID(key = "paranoid", id = 2, memoryKiB = 262_144, passes = 4, parallelism = 4);

    companion object {
        fun fromKey(key: String?): Spv2Tier = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

enum class Spv2ErrorCode {
    EMPTY_INPUT,
    HKDF_EXHAUSTED,
    KAT_ARGON2ID_FAILED,
    KAT_SHA256_FAILED,
    KAT_HKDF_FAILED,
    OUT_OF_MEMORY,
    DERIVE_ERROR
}

class Spv2Exception(
    val code: Spv2ErrorCode,
    override val message: String = code.name,
    cause: Throwable? = null
) : Exception(message, cause)
