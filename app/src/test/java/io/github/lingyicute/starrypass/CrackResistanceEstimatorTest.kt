package io.github.lingyicute.starrypass

import io.github.lingyicute.starrypass.crypto.CrackResistanceEstimator
import io.github.lingyicute.starrypass.crypto.Spv2Mode
import io.github.lingyicute.starrypass.crypto.Spv2Tier
import io.github.lingyicute.starrypass.crypto.StrengthReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrackResistanceEstimatorTest {

    @Test
    fun `detects weak patterns identically to upstream web estimator`() {
        // Empty input
        assertEquals(
            0,
            CrackResistanceEstimator.estimate("", "github", Spv2Tier.STANDARD, Spv2Mode.FULL, 16).level
        )

        // Master contains site code
        val containsCode = CrackResistanceEstimator.estimate(
            "MyGitHub2026!", "github", Spv2Tier.STANDARD, Spv2Mode.FULL, 16
        )
        assertEquals(1, containsCode.level)
        assertEquals(StrengthReason.WEAK_CONTAINS_CODE, containsCode.reason)

        // Pure YYYYMMDD date
        val pureDate = CrackResistanceEstimator.estimate(
            "19990815", "bank", Spv2Tier.STANDARD, Spv2Mode.FULL, 16
        )
        assertEquals(1, pureDate.level)
        assertEquals(StrengthReason.WEAK_PURE_DATE, pureDate.reason)

        // Common weak root with leet + trailing digits/symbols
        val weakLeet = CrackResistanceEstimator.estimate(
            "p@ssw0rd99#", "bank", Spv2Tier.STANDARD, Spv2Mode.FULL, 16
        )
        assertEquals(1, weakLeet.level)
        assertEquals(StrengthReason.WEAK_COMMON_ROOT, weakLeet.reason)
    }

    @Test
    fun `weak roots and suffix boundaries match upstream web estimator`() {
        // Expected values sampled from the upstream estimator at 8d5dd930.
        for (master in listOf(
            "password2026!", "p@ssw0rd99#", "abc123!", "football2026!",
            "secret", "admintelecom", "Passw0rd2026!", "secret12345678"
        )) {
            val estimate = CrackResistanceEstimator.estimate(
                master, "example", Spv2Tier.STANDARD, Spv2Mode.FULL, 16
            )
            assertEquals(master, StrengthReason.WEAK_COMMON_ROOT, estimate.reason)
            assertEquals(master, 18, estimate.bits)
        }
        val longSuffix = CrackResistanceEstimator.estimate(
            "secret123456789", "example", Spv2Tier.STANDARD, Spv2Mode.FULL, 16
        )
        assertEquals(StrengthReason.WEAK_SHORT, longSuffix.reason)
        assertEquals(42, longSuffix.bits)

        val notAWeakRoot = CrackResistanceEstimator.estimate(
            "constructor", "example", Spv2Tier.STANDARD, Spv2Mode.FULL, 16
        )
        assertEquals(StrengthReason.WEAK_SHORT, notAWeakRoot.reason)
        assertEquals(35, notAWeakRoot.bits)
    }

    @Test
    fun `scores passphrases CJK emoji and PIN caps accurately`() {
        val strongChinese = CrackResistanceEstimator.estimate(
            "星垂平野阔，月涌大江流！2026", "github", Spv2Tier.STANDARD, Spv2Mode.FULL, 20
        )
        assertTrue(strongChinese.level >= 3)
        assertTrue(strongChinese.bits >= 70)

        val veryStrong = CrackResistanceEstimator.estimate(
            "correct horse battery staple", "github", Spv2Tier.PARANOID, Spv2Mode.FULL, 16
        )
        assertEquals(4, veryStrong.level)
        assertEquals(StrengthReason.VERY_STRONG, veryStrong.reason)

        // A PIN caps at level 2 (relying on terminal rate limiting) even with a strong master
        val pinEst = CrackResistanceEstimator.estimate(
            "correct horse battery staple", "cmb-card", Spv2Tier.STANDARD, Spv2Mode.PIN, 6
        )
        assertEquals(2, pinEst.level)
        assertEquals(StrengthReason.PIN_TERMINAL_LIMITED, pinEst.reason)
    }

    @Test
    fun `short outputs bound the verdict like the upstream web estimator`() {
        val master = "correct horse battery staple"
        val fullShort = CrackResistanceEstimator.estimate(
            master, "github", Spv2Tier.PARANOID, Spv2Mode.FULL, 4
        )
        assertEquals(StrengthReason.WEAK_SHORT, fullShort.reason)
        assertEquals(25, fullShort.bits)

        val alnum = CrackResistanceEstimator.estimate(
            master, "cmb-card", Spv2Tier.STANDARD, Spv2Mode.ALNUM, 8
        )
        assertEquals(StrengthReason.MEDIUM, alnum.reason)
        assertEquals(48, alnum.bits)

        val longPin = CrackResistanceEstimator.estimate(
            master, "cmb-card", Spv2Tier.STANDARD, Spv2Mode.PIN, 12
        )
        assertEquals(StrengthReason.PIN_TERMINAL_LIMITED, longPin.reason)
        assertEquals(40, longPin.bits)

        val shortDate = CrackResistanceEstimator.estimate(
            "19990815", "bank", Spv2Tier.STANDARD, Spv2Mode.PIN, 4
        )
        assertEquals(StrengthReason.WEAK_PURE_DATE, shortDate.reason)
        assertEquals(13, shortDate.bits)
    }

    @Test
    fun `decorated and repeated inputs match the upstream web estimator`() {
        // Decoration before the root (2026!password) and an apostrophe after it.
        for (master in listOf("2026!password", "password'")) {
            val estimate = CrackResistanceEstimator.estimate(
                master, "example", Spv2Tier.STANDARD, Spv2Mode.FULL, 16
            )
            assertEquals(master, StrengthReason.WEAK_COMMON_ROOT, estimate.reason)
            assertEquals(master, 18, estimate.bits)
        }

        // A repeated date is still a date; a repeated unit is scored once plus its repeat count.
        val repeatedDate = CrackResistanceEstimator.estimate(
            "1999081519990815", "bank", Spv2Tier.STANDARD, Spv2Mode.FULL, 16
        )
        assertEquals(StrengthReason.WEAK_PURE_DATE, repeatedDate.reason)
        assertEquals(15, repeatedDate.bits)

        val repeatedUnit = CrackResistanceEstimator.estimate(
            "ab".repeat(16), "example", Spv2Tier.STANDARD, Spv2Mode.FULL, 16
        )
        assertEquals(StrengthReason.WEAK_SHORT, repeatedUnit.reason)
        assertEquals(10, repeatedUnit.bits)

        val repeatedPhrase = CrackResistanceEstimator.estimate(
            "correct horse battery staple ".repeat(2).trimEnd(), "example",
            Spv2Tier.STANDARD, Spv2Mode.FULL, 16
        )
        assertEquals(StrengthReason.VERY_STRONG, repeatedPhrase.reason)
        assertEquals(100, repeatedPhrase.bits)
    }

    @Test
    fun `a one character emoji code is not counted as contained in the master`() {
        val estimate = CrackResistanceEstimator.estimate(
            "Tr0ub4dor&3-xyz!Qw\uD83E\uDD84", "\uD83E\uDD84", Spv2Tier.STANDARD, Spv2Mode.FULL, 16
        )
        assertEquals(StrengthReason.VERY_STRONG, estimate.reason)
        assertEquals(100, estimate.bits)
    }
}
