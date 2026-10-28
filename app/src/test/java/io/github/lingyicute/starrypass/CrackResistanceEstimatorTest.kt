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

        // PIN <= 8 digits caps at level 2 (relying on terminal rate limiting) even with strong master
        val pinEst = CrackResistanceEstimator.estimate(
            "correct horse battery staple", "cmb-card", Spv2Tier.STANDARD, Spv2Mode.PIN, 6
        )
        assertEquals(2, pinEst.level)
        assertEquals(StrengthReason.PIN_TERMINAL_LIMITED, pinEst.reason)
    }
}
