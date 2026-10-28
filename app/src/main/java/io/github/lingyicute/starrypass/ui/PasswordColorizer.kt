/*
 * StarryPass Android — UI Formatting & Tonal Password Highlighting Helpers
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.github.lingyicute.starrypass.R
import io.github.lingyicute.starrypass.crypto.Spv2Mode
import io.github.lingyicute.starrypass.crypto.Spv2Tier
import io.github.lingyicute.starrypass.crypto.StrengthEstimate
import io.github.lingyicute.starrypass.crypto.StrengthReason

fun buildColorizedPassword(
    password: String,
    colorize: Boolean,
    masked: Boolean,
    colorScheme: ColorScheme
): AnnotatedString = buildAnnotatedString {
    if (masked) {
        withStyle(SpanStyle(color = colorScheme.onSurfaceVariant)) {
            append("•".repeat(password.length))
        }
        return@buildAnnotatedString
    }
    if (!colorize) {
        withStyle(SpanStyle(color = colorScheme.onSurface)) {
            append(password)
        }
        return@buildAnnotatedString
    }
    for (ch in password) {
        val color = when {
            ch in '0'..'9' -> colorScheme.primary
            ch in 'a'..'z' || ch in 'A'..'Z' -> colorScheme.onSurface
            else -> colorScheme.tertiary
        }
        withStyle(SpanStyle(color = color)) {
            append(ch)
        }
    }
}

@Composable
fun strengthLabelText(estimate: StrengthEstimate): String = when (estimate.reason) {
    StrengthReason.EMPTY -> stringResource(R.string.strength_empty)
    StrengthReason.WEAK_CONTAINS_CODE -> stringResource(R.string.strength_weak_code)
    StrengthReason.WEAK_PURE_DATE -> stringResource(R.string.strength_weak_date)
    StrengthReason.WEAK_COMMON_ROOT -> stringResource(R.string.strength_weak_common)
    StrengthReason.WEAK_SHORT_FOR_PIN -> stringResource(R.string.strength_weak_pin_short)
    StrengthReason.PIN_TERMINAL_LIMITED -> stringResource(R.string.strength_pin_limited)
    StrengthReason.WEAK_SHORT -> stringResource(R.string.strength_weak_short, estimate.bits)
    StrengthReason.MEDIUM -> stringResource(R.string.strength_medium, estimate.bits)
    StrengthReason.STRONG -> stringResource(R.string.strength_strong, estimate.bits)
    StrengthReason.VERY_STRONG -> stringResource(R.string.strength_very_strong, estimate.bits)
}

@Composable
fun modeLabelText(mode: Spv2Mode): String = when (mode) {
    Spv2Mode.FULL -> stringResource(R.string.mode_full)
    Spv2Mode.ALNUM -> stringResource(R.string.mode_alnum)
    Spv2Mode.PIN -> stringResource(R.string.mode_pin)
}

@Composable
fun tierShortLabelText(tier: Spv2Tier): String = when (tier) {
    Spv2Tier.STANDARD -> stringResource(R.string.tier_standard_short)
    Spv2Tier.PARANOID -> stringResource(R.string.tier_paranoid_short)
}
