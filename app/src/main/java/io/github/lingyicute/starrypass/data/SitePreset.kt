/*
 * StarryPass Android — Non-sensitive Site Policy Preset Model
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.data

import io.github.lingyicute.starrypass.crypto.Spv2Mode
import io.github.lingyicute.starrypass.crypto.Spv2Spec
import io.github.lingyicute.starrypass.crypto.Spv2Tier
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Optional local preset storing ONLY non-sensitive site parameters:
 *   - Site code (`code`), display alias, length, character mode, rotation counter, KDF tier,
 *     and optional domain / package hints for Android Autofill matching.
 *
 * By design, this model NEVER contains or persists the master password or derived output.
 */
@Serializable
data class SitePreset(
    val id: String = UUID.randomUUID().toString(),
    val code: String,
    val alias: String = "",
    val modeKey: String = Spv2Mode.FULL.key,
    val length: Int = Spv2Spec.DEFAULT_LENGTH,
    val counter: Int = Spv2Spec.DEFAULT_COUNTER,
    val tierKey: String = Spv2Tier.STANDARD.key,
    val autofillDomain: String = "",
    val autofillPackage: String = "",
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    val mode: Spv2Mode
        get() = Spv2Mode.fromKey(modeKey)

    val tier: Spv2Tier
        get() = Spv2Tier.fromKey(tierKey)

    val displayName: String
        get() = alias.ifBlank { code }
}

sealed interface PresetRotationResult {
    data class Updated(val preset: SitePreset) : PresetRotationResult
    data object LimitReached : PresetRotationResult
    data object NotFound : PresetRotationResult
}

enum class ThemePreference(val storageKey: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemePreference =
            entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

data class AppSettings(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val dynamicColor: Boolean = true,
    val flagSecure: Boolean = true,
    val wipeOnBackground: Boolean = true,
    val clipboardAutoClearSeconds: Int = 45,
    val colorizePassword: Boolean = true
)
