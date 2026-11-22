/*
 * StarryPass Android — Master Password Crack-Resistance Estimator
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 *
 * Exact Unicode-codepoint-aware port of `estimateCrackResistance` from upstream `index.html`.
 */
package io.github.lingyicute.starrypass.crypto

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log2
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

    private val MAX_WEAK_ROOT_LEN = WEAK_ROOTS.maxOf { it.length }

    private val KB_ROWS = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val PURE_DATE_REGEX = Regex("^(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])$")

    // Decoration characters that people put around a weak root (upstream WEAK_SUFFIX_RE).
    private const val WEAK_SUFFIX_CHARS = "0123456789!@#\$%^&*()_+-=[]{};:'\"\\|,.<>/?~`"

    // Bounded window used to look for a repeated unit starting at one position.
    private const val RUN_WINDOW = 256

    private class Run(val period: Int, val length: Int)

    private fun isWeakSuffixChar(ch: Char): Boolean = WEAK_SUFFIX_CHARS.indexOf(ch) >= 0

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

    private fun utf16Units(value: String): IntArray = IntArray(value.length) { value[it].code }

    /** KMP prefix function: the shortest period of `s[0..m-1]` is `m - fail[m - 1]`. */
    private fun prefixTable(units: IntArray): IntArray {
        val fail = IntArray(units.size)
        var k = 0
        for (i in 1 until units.size) {
            while (k > 0 && units[i] != units[k]) k = fail[k - 1]
            if (units[i] == units[k]) k++
            fail[i] = k
        }
        return fail
    }

    private fun shortestPeriod(units: IntArray): Int =
        if (units.isEmpty()) 0 else units.size - prefixTable(units)[units.size - 1]

    /**
     * Longest run from [start] that repeats a unit at least twice, searched in a bounded window
     * and then extended. Returned only when collapsing it is clearly cheaper than scoring every
     * character, where each further character adds at least 0.5 bits.
     */
    private fun repeatedRun(cps: IntArray, start: Int, limit: Int, fail: IntArray): Run? {
        val w = minOf(limit - start, RUN_WINDOW)
        var k = 0
        var best = 0
        var period = 0
        var hit = false
        var p = 1
        // Cheap necessary condition first: the unit's first and last characters recur one period later.
        while (p * 2 <= w && !hit) {
            hit = cps[start + p] == cps[start] && cps[start + 2 * p - 1] == cps[start + p - 1]
            p++
        }
        if (!hit) return null
        for (j in 1 until w) {
            while (k > 0 && cps[start + j] != cps[start + k]) k = fail[k - 1]
            if (cps[start + j] == cps[start + k]) k++
            fail[j] = k
            val candidate = j + 1 - k
            if (candidate * 2 <= j + 1) {
                best = j + 1
                period = candidate
            }
        }
        if (best == 0) return null
        while (start + best < limit && cps[start + best] == cps[start + best - period]) best++
        return if (log2(best.toDouble() / period) < 0.5 * (best - period)) Run(period, best) else null
    }

    /** Decorations may precede a weak root too (`2026!password`): skip at most 8 of them. */
    private fun hasWeakRoot(value: String): Boolean {
        var start = 0
        while (start <= 8 && start < value.length) {
            if (start > 0 && !isWeakSuffixChar(value[start - 1])) return false
            if (hasWeakRootFrom(if (start > 0) value.substring(start) else value)) return true
            start++
        }
        return false
    }

    private fun hasWeakRootFrom(value: String): Boolean {
        val n = value.length
        if (n == 0) return false
        // One KMP table answers the shortest period of any prefix in O(1); memoising by prefix
        // length avoids exponential recursion.
        val fail = prefixTable(utf16Units(value))
        val seen = HashSet<Int>()
        var prefixLimit = 0
        while (prefixLimit < 8 && prefixLimit < n && isWeakSuffixChar(value[prefixLimit])) prefixLimit++

        fun scan(startLen: Int, folded: Boolean): Boolean {
            if (!seen.add(startLen)) return false
            var len = startLen
            var strip = 0
            // Strip before de-leeting and test every boundary, including digit-ending roots
            // (`abc123!`), and fold pure repetitions (`P@ssw0rd2026!P@ssw0rd2026!`).
            while (len > 0) {
                // A complete period can carry its own leading decoration; the caller already
                // handles prefixes on the whole input.
                val lastStart = if (folded) minOf(prefixLimit, len - 1) else 0
                var start = maxOf(0, len - MAX_WEAK_ROOT_LEN)
                while (start <= lastStart) {
                    if (isWeakRoot(value.substring(start, len))) return true
                    start++
                }
                val period = len - fail[len - 1]
                if (period > 0 && period * 2 <= len) {
                    if (scan(period, true)) return true
                    // A weak root may itself repeat (zaq1zaq1, 000000, 121212).
                    val maxMult = minOf(len ushr 1, MAX_WEAK_ROOT_LEN + 8 + prefixLimit)
                    var mult = period * 2
                    while (mult <= maxMult) {
                        if (scan(mult, true)) return true
                        mult += period
                    }
                }
                if (strip >= 8 || !isWeakSuffixChar(value[len - 1])) return false
                strip++
                len--
            }
            return false
        }
        return scan(n, false)
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

        // Theoretical upper bound of the output space, not an exact entropy: no verdict can
        // claim more bits than the generated password can carry.
        val poolSize = when (mode) {
            Spv2Mode.PIN -> 10
            Spv2Mode.ALNUM -> 62
            Spv2Mode.FULL -> 77
        }
        val outBits = Spv2Spec.clampLength(length) * log2(poolSize.toDouble())

        val mLower = m.lowercase(Locale.ROOT)
        val cLower = c.lowercase(Locale.ROOT)
        // Count code points before case folding: one astral character, or an `İ` that lowercases
        // to two code points, is still a one-character code.
        if (c.codePointCount(0, c.length) >= 2 && mLower.contains(cLower)) {
            return StrengthEstimate(
                level = 1,
                bits = minOf(16.0, outBits).roundToInt(),
                reason = StrengthReason.WEAK_CONTAINS_CODE
            )
        }
        // Only a date prefix can form a repeated date; skip the extra KMP table otherwise.
        if (PURE_DATE_REGEX.matches(m) ||
            (m.length >= 16 && PURE_DATE_REGEX.matches(m.substring(0, 8)) && shortestPeriod(utf16Units(m)) == 8)
        ) {
            return StrengthEstimate(
                level = 1,
                bits = minOf(15.0, outBits).roundToInt(),
                reason = StrengthReason.WEAK_PURE_DATE
            )
        }

        if (hasWeakRoot(mLower)) {
            return StrengthEstimate(
                level = 1,
                bits = minOf(18.0, outBits).roundToInt(),
                reason = StrengthReason.WEAK_COMMON_ROOT
            )
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
        val asciiBits = if (asciiPool > 0) log2(asciiPool.toDouble()) else 0.0

        // A string built from a short repeating unit (aaaa…, abcabc…) counts one unit plus the
        // information in the repeat count; otherwise a very long repeat would add up to "strong".
        val n = cps.size
        var unitLen = shortestPeriod(cps)
        if (unitLen * 2 > n) unitLen = n
        // A repeat inside a longer string (abab…abX) is collapsed the same way; nested repeats
        // inside a collapsed unit are folded too, via a stack of (unit end, run end) boundaries.
        val skipUnitEnds = ArrayList<Int>()
        val skipRunEnds = ArrayList<Int>()
        var repeatBits = 0.0
        if (unitLen < n) {
            skipUnitEnds.add(unitLen)
            skipRunEnds.add(n)
            repeatBits += log2(n.toDouble() / unitLen)
        }
        val runFail = IntArray(RUN_WINDOW)

        var bits = 0.0
        val seen = HashMap<Int, Int>()
        var i = 0
        while (i < n) {
            var limit = if (skipUnitEnds.isEmpty()) n else skipUnitEnds[skipUnitEnds.size - 1]
            while (true) {
                val run = repeatedRun(cps, i, limit, runFail) ?: break
                limit = i + run.period
                skipUnitEnds.add(limit)
                skipRunEnds.add(i + run.length)
                repeatBits += log2(run.length.toDouble() / run.period)
            }
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
            // Leaving a collapsed unit: skip the remaining repeats of it.
            while (skipUnitEnds.isNotEmpty() && i + 1 == skipUnitEnds[skipUnitEnds.size - 1]) {
                skipUnitEnds.removeAt(skipUnitEnds.size - 1)
                i = skipRunEnds.removeAt(skipRunEnds.size - 1) - 1
            }
            i++
        }
        bits += repeatBits

        if (tier == Spv2Tier.PARANOID) {
            bits += 3.0
        }

        if (mode == Spv2Mode.PIN) {
            // A PIN is protected by the verifier's attempt limit, so the level stays at "medium"
            // at best, but the number of bits must reflect the real output space.
            val shown = minOf(bits, outBits).roundToInt()
            return if (bits < 44.0 || outBits <= 0.0) {
                StrengthEstimate(level = 1, bits = shown, reason = StrengthReason.WEAK_SHORT_FOR_PIN)
            } else {
                StrengthEstimate(level = 2, bits = shown, reason = StrengthReason.PIN_TERMINAL_LIMITED)
            }
        }

        bits = minOf(bits, outBits)
        val rounded = bits.roundToInt()
        return when {
            bits < 44.0 -> StrengthEstimate(level = 1, bits = rounded, reason = StrengthReason.WEAK_SHORT)
            bits < 64.0 -> StrengthEstimate(level = 2, bits = rounded, reason = StrengthReason.MEDIUM)
            bits < 86.0 -> StrengthEstimate(level = 3, bits = rounded, reason = StrengthReason.STRONG)
            else -> StrengthEstimate(level = 4, bits = rounded, reason = StrengthReason.VERY_STRONG)
        }
    }
}
