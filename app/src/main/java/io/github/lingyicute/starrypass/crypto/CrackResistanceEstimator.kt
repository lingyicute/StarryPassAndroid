/*
 * StarryPass Android — Master Password Crack-Resistance Estimator
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 *
 * Exact Unicode-codepoint-aware port of `estimateCrackResistance` from upstream `index.html`.
 */
package io.github.lingyicute.starrypass.crypto

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

enum class StrengthReason {
    EMPTY,
    WEAK_CONTAINS_CODE,
    WEAK_PURE_DATE,
    WEAK_COMMON_ROOT,
    WEAK_SHORT_FOR_PIN,
    PIN_TERMINAL_LIMITED,
    WEAK_SHORT,
    MEDIUM,
    STRONG,
    VERY_STRONG
}

data class StrengthEstimate(
    val level: Int, // 0..4
    val bits: Int,
    val reason: StrengthReason
)

object CrackResistanceEstimator {

    // Keep this list and the suffix handling in sync with upstream index.html.
    private val WEAK_ROOTS = setOf(
        "123", "1234", "12345", "123456", "1234567", "12345678",
        "123456789", "1234567890", "12345678910", "0123456789",
        "1111", "111111", "1111111", "000000", "666666", "888888",
        "7777777", "555555", "654321", "123123", "123321", "112233",
        "121212", "102030", "987654321", "5201314", "1314520", "7758521",
        "password", "passwd", "passw0rd", "p@ssw0rd", "p@ssword",
        "password1", "password123", "pass@123", "pass123", "pass1234",
        "admin", "administrator", "admin123", "admin@123", "admintelecom",
        "root", "login", "welcome", "welcome@123", "letmein", "secret",
        "trustno1", "master", "access", "guest", "user", "test", "test1",
        "changeme", "default", "pass",
        "qwerty", "qwerty1", "qwerty123", "qwertyuiop", "asdfgh", "asdfghjkl",
        "zxcvbn", "zxcvbnm", "1q2w3e", "1q2w3e4r", "1q2w3e4r5t",
        "1qaz2wsx", "qazwsx", "qazwsxedc", "123qwe", "qwe123",
        "azerty", "zaq1zaq1", "abc123", "abcd1234", "abcd@1234", "aa123456",
        "aa@123456", "a123456", "abc@123", "iloveyou", "loveme", "lovely",
        "monkey", "dragon", "football", "baseball", "princess", "sunshine",
        "shadow", "superman", "batman", "starwars", "hello", "flower", "charlie",
        "minecraft", "google", "woaini", "qq123456", "a123456789",
        "starrypass", "huami", "flowerpassword"
    )

    private val KB_ROWS = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val PURE_DATE_REGEX = Regex("^(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])$")
    private const val TRAILING_NOISE = "0123456789!@#\$%^&*()_+-=[]{};:\"\\|,.<>/?~`"

    private fun isKeyboardStep(cpA: Int, cpB: Int): Boolean {
        val la = cpA.toChar().lowercaseChar()
        val lb = cpB.toChar().lowercaseChar()
        if (la == lb) return false
        for (row in KB_ROWS) {
            val ia = row.indexOf(la)
            val ib = row.indexOf(lb)
            if (ia != -1 && ib != -1 && abs(ia - ib) == 1) return true
        }
        return false
    }

    private fun isWeakRoot(value: String): Boolean {
        val deLeet = buildString(value.length) {
            for (ch in value) {
                append(
                    when (ch) {
                        '@', '4' -> 'a'
                        '0' -> 'o'
                        '1', '!', '|' -> 'i'
                        '3' -> 'e'
                        '$', '5' -> 's'
                        '7' -> 't'
                        else -> ch
                    }
                )
            }
        }
        return value in WEAK_ROOTS || deLeet in WEAK_ROOTS
    }

    private fun hasWeakRoot(value: String): Boolean {
        if (isWeakRoot(value)) return true
        var stem = value
        // Strip before de-leeting and test every boundary, including digit-ending roots.
        repeat(8) {
            if (stem.isEmpty() || stem.last() !in TRAILING_NOISE) return false
            stem = stem.dropLast(1)
            if (isWeakRoot(stem)) return true
        }
        return false
    }

    fun estimate(
        master: String,
        code: String,
        tier: Spv2Tier,
        mode: Spv2Mode,
        length: Int
    ): StrengthEstimate {
        val m = Normalizer.normalize(master, Normalizer.Form.NFC)
        val c = Normalizer.normalize(Spv2Spec.ecmaTrim(code), Normalizer.Form.NFC)
        if (m.isEmpty() || c.isEmpty()) {
            return StrengthEstimate(level = 0, bits = 0, reason = StrengthReason.EMPTY)
        }

        val mLower = m.lowercase()
        val cLower = c.lowercase()
        if (cLower.length >= 2 && mLower.contains(cLower)) {
            return StrengthEstimate(level = 1, bits = 16, reason = StrengthReason.WEAK_CONTAINS_CODE)
        }
        if (PURE_DATE_REGEX.matches(m)) {
            return StrengthEstimate(level = 1, bits = 15, reason = StrengthReason.WEAK_PURE_DATE)
        }

        if (hasWeakRoot(mLower)) {
            return StrengthEstimate(level = 1, bits = 18, reason = StrengthReason.WEAK_COMMON_ROOT)
        }

        // Split by Unicode code points (matching JS `Array.from(m)`).
        val cps = m.codePoints().toArray()
        var hasLower = false
        var hasUpper = false
        var hasDigit = false
        var hasSym = false
        for (cp in cps) {
            when (cp) {
                in 97..122 -> hasLower = true
                in 65..90 -> hasUpper = true
                in 48..57 -> hasDigit = true
                in 32..126 -> hasSym = true
            }
        }
        val asciiPool = (if (hasLower) 26 else 0) +
            (if (hasUpper) 26 else 0) +
            (if (hasDigit) 10 else 0) +
            (if (hasSym) 33 else 0)
        val asciiBits = if (asciiPool > 0) ln(asciiPool.toDouble()) / ln(2.0) else 0.0

        var bits = 0.0
        val seen = HashMap<Int, Int>()
        for (i in cps.indices) {
            val cp = cps[i]
            val baseBits = when {
                cp in 32..126 -> asciiBits
                (cp in 0x3400..0x9FFF) || (cp in 0x20000..0x2EBEF) -> 7.5
                else -> 6.5
            }
            val prevCp = if (i > 0) cps[i - 1] else -999
            val count = seen[cp] ?: 0
            bits += when {
                prevCp == cp -> 0.5
                abs(cp - prevCp) == 1 || (i > 0 && cp < 128 && prevCp < 128 && isKeyboardStep(prevCp, cp)) -> 1.2
                count == 0 -> baseBits
                count == 1 -> baseBits * 0.65
                else -> baseBits * 0.35
            }
            seen[cp] = count + 1
        }

        if (tier == Spv2Tier.PARANOID) {
            bits += 3.0
        }

        val rounded = bits.roundToInt()
        if (mode == Spv2Mode.PIN && length <= 8) {
            return if (bits < 44.0) {
                StrengthEstimate(level = 1, bits = rounded, reason = StrengthReason.WEAK_SHORT_FOR_PIN)
            } else {
                StrengthEstimate(level = 2, bits = rounded, reason = StrengthReason.PIN_TERMINAL_LIMITED)
            }
        }

        return when {
            bits < 44.0 -> StrengthEstimate(level = 1, bits = rounded, reason = StrengthReason.WEAK_SHORT)
            bits < 64.0 -> StrengthEstimate(level = 2, bits = rounded, reason = StrengthReason.MEDIUM)
            bits < 86.0 -> StrengthEstimate(level = 3, bits = rounded, reason = StrengthReason.STRONG)
            else -> StrengthEstimate(level = 4, bits = rounded, reason = StrengthReason.VERY_STRONG)
        }
    }
}
