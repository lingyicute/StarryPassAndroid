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

    private val WEAK_ROOTS = setOf(
        "123456", "12345678", "123456789", "1234567890", "111111", "000000",
        "666666", "888888", "5201314", "password", "passwd", "qwerty",
        "qwertyuiop", "asdfgh", "asdfghjkl", "zxcvbn", "zxcvbnm", "abc123",
        "iloveyou", "admin", "administrator", "root", "letmein", "welcome",
        "monkey", "dragon", "master", "starrypass", "huami", "flowerpassword",
        "1q2w3e4r", "qazwsx"
    )

    private val KB_ROWS = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val PURE_DATE_REGEX = Regex("^(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])$")
    private val TRAILING_NOISE_REGEX = Regex("[0-9!@#\$%^&*()_+\\-=\\[\\]{};:\"\\\\|,.<>/?~`]+$")

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

        val deLeet = buildString(mLower.length) {
            for (ch in mLower) {
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
        val stem = deLeet.replace(TRAILING_NOISE_REGEX, "")
        if (mLower in WEAK_ROOTS || (stem in WEAK_ROOTS && m.length - stem.length <= 6)) {
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
