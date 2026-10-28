/*
 * StarryPass Android — Preferences & Non-sensitive Preset Repository
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.lingyicute.starrypass.crypto.Spv2Spec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.starryPassDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "starrypass_preferences"
)

class PreferencesRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val FLAG_SECURE = booleanPreferencesKey("flag_secure")
        val WIPE_ON_BACKGROUND = booleanPreferencesKey("wipe_on_background")
        val CLIPBOARD_AUTO_CLEAR_SEC = intPreferencesKey("clipboard_auto_clear_sec")
        val COLORIZE_PASSWORD = booleanPreferencesKey("colorize_password")
        val SITE_PRESETS_JSON = stringPreferencesKey("site_presets_json")
    }

    val settingsFlow: Flow<AppSettings> = context.starryPassDataStore.data.map { prefs ->
        AppSettings(
            theme = ThemePreference.fromKey(prefs[Keys.THEME]),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            flagSecure = prefs[Keys.FLAG_SECURE] ?: true,
            wipeOnBackground = prefs[Keys.WIPE_ON_BACKGROUND] ?: true,
            clipboardAutoClearSeconds = prefs[Keys.CLIPBOARD_AUTO_CLEAR_SEC] ?: 45,
            colorizePassword = prefs[Keys.COLORIZE_PASSWORD] ?: true
        )
    }

    val presetsFlow: Flow<List<SitePreset>> = context.starryPassDataStore.data.map { prefs ->
        val raw = prefs[Keys.SITE_PRESETS_JSON]
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<SitePreset>>(raw) }
                .getOrDefault(emptyList())
                .sortedByDescending { it.updatedAtEpochMs }
        }
    }

    suspend fun setTheme(theme: ThemePreference) {
        context.starryPassDataStore.edit { it[Keys.THEME] = theme.storageKey }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.starryPassDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setFlagSecure(enabled: Boolean) {
        context.starryPassDataStore.edit { it[Keys.FLAG_SECURE] = enabled }
    }

    suspend fun setWipeOnBackground(enabled: Boolean) {
        context.starryPassDataStore.edit { it[Keys.WIPE_ON_BACKGROUND] = enabled }
    }

    suspend fun setClipboardAutoClearSeconds(seconds: Int) {
        context.starryPassDataStore.edit { it[Keys.CLIPBOARD_AUTO_CLEAR_SEC] = seconds.coerceAtLeast(0) }
    }

    suspend fun setColorizePassword(enabled: Boolean) {
        context.starryPassDataStore.edit { it[Keys.COLORIZE_PASSWORD] = enabled }
    }

    suspend fun upsertPreset(preset: SitePreset) {
        val cleanCode = Spv2Spec.ecmaTrim(preset.code)
        if (cleanCode.isEmpty()) return
        val normalized = preset.copy(
            code = cleanCode,
            alias = preset.alias.trim(),
            length = Spv2Spec.clampLength(preset.length),
            counter = Spv2Spec.clampCounter(preset.counter),
            autofillDomain = normalizeDomain(preset.autofillDomain),
            autofillPackage = preset.autofillPackage.trim(),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        context.starryPassDataStore.edit { prefs ->
            val current = decodePresets(prefs[Keys.SITE_PRESETS_JSON]).toMutableList()
            val idx = current.indexOfFirst {
                it.id == normalized.id || it.code == normalized.code
            }
            if (idx >= 0) {
                current[idx] = normalized.copy(id = current[idx].id)
            } else {
                current.add(0, normalized)
            }
            prefs[Keys.SITE_PRESETS_JSON] = json.encodeToString(current)
        }
    }

    suspend fun incrementPresetCounter(presetId: String): SitePreset? {
        var updated: SitePreset? = null
        context.starryPassDataStore.edit { prefs ->
            val current = decodePresets(prefs[Keys.SITE_PRESETS_JSON]).toMutableList()
            val idx = current.indexOfFirst { it.id == presetId }
            if (idx >= 0) {
                val item = current[idx]
                val next = item.copy(
                    counter = Spv2Spec.clampCounter(item.counter + 1),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                current[idx] = next
                updated = next
                prefs[Keys.SITE_PRESETS_JSON] = json.encodeToString(current)
            }
        }
        return updated
    }

    suspend fun deletePreset(presetId: String) {
        context.starryPassDataStore.edit { prefs ->
            val current = decodePresets(prefs[Keys.SITE_PRESETS_JSON])
                .filterNot { it.id == presetId }
            prefs[Keys.SITE_PRESETS_JSON] = json.encodeToString(current)
        }
    }

    suspend fun clearAllPresets() {
        context.starryPassDataStore.edit { prefs ->
            prefs.remove(Keys.SITE_PRESETS_JSON)
        }
    }

    /**
     * Matches an incoming Autofill web domain or Android package name against saved presets.
     */
    suspend fun findMatchingPreset(webDomain: String?, packageName: String?): SitePreset? {
        val presets = presetsFlow.first()
        if (presets.isEmpty()) return null

        val normDomain = webDomain?.let(::normalizeDomain)?.takeIf { it.isNotEmpty() }
        val normPkg = packageName?.trim()?.takeIf { it.isNotEmpty() }

        if (normDomain != null) {
            presets.firstOrNull { preset ->
                val pd = preset.autofillDomain
                pd.isNotEmpty() && (normDomain == pd || normDomain.endsWith(".$pd"))
            }?.let { return it }

            val domainStem = extractDomainStem(normDomain)
            presets.firstOrNull { preset ->
                preset.code.equals(normDomain, ignoreCase = true) ||
                    (domainStem.isNotEmpty() && preset.code.equals(domainStem, ignoreCase = true))
            }?.let { return it }
        }

        if (normPkg != null) {
            presets.firstOrNull { preset ->
                preset.autofillPackage.isNotEmpty() && preset.autofillPackage.equals(normPkg, ignoreCase = true)
            }?.let { return it }
        }

        return null
    }

    private fun decodePresets(raw: String?): List<SitePreset> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<SitePreset>>(raw) }.getOrDefault(emptyList())
    }

    companion object {
        fun normalizeDomain(raw: String): String {
            var s = raw.trim().lowercase()
            s = s.removePrefix("https://").removePrefix("http://")
            val slash = s.indexOf('/')
            if (slash >= 0) s = s.substring(0, slash)
            val colon = s.indexOf(':')
            if (colon >= 0) s = s.substring(0, colon)
            s = s.removePrefix("www.")
            return s
        }

        /**
         * Suggests a clean StarryPass site code from a web domain or Android package name.
         * e.g. `accounts.github.com` -> `github`, `com.protonmail.android` -> `protonmail`.
         */
        fun suggestCodeFromContext(webDomain: String?, packageName: String?): String {
            if (!webDomain.isNullOrBlank()) {
                val stem = extractDomainStem(normalizeDomain(webDomain))
                if (stem.isNotEmpty()) return stem
            }
            if (!packageName.isNullOrBlank()) {
                val rawParts = packageName.lowercase().split('.').filter { it.isNotEmpty() }
                val meaningful = rawParts
                    .drop(if (rawParts.size >= 2) 1 else 0) // drop leading TLD (com, org, ch, de, io, jp, ...)
                    .filterNot {
                        it in setOf(
                            "com", "org", "net", "io", "app", "me", "co", "cn", "hk", "tw",
                            "android", "mobile", "client", "release", "debug"
                        )
                    }
                if (meaningful.isNotEmpty()) return meaningful.first()
            }
            return ""
        }

        fun extractDomainStem(domain: String): String {
            val clean = normalizeDomain(domain)
            if (clean.isEmpty()) return ""
            val parts = clean.split('.').filter { it.isNotEmpty() }
            if (parts.size <= 1) return clean
            val multiPartTlds = setOf("co.uk", "org.uk", "com.cn", "net.cn", "org.cn", "com.hk", "com.tw", "co.jp")
            val tail2 = parts.takeLast(2).joinToString(".")
            return if (parts.size >= 3 && tail2 in multiPartTlds) {
                parts[parts.size - 3]
            } else {
                parts[parts.size - 2]
            }
        }
    }
}
