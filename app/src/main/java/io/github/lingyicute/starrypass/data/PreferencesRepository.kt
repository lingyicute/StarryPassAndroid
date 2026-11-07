/*
 * StarryPass Android — Preferences & Non-sensitive Preset Repository
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.lingyicute.starrypass.crypto.Spv2Spec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

private const val DATASTORE_NAME = "starrypass_preferences"

@Volatile private var sharedDataStore: DataStore<Preferences>? = null

private fun Context.starryPassDataStore(): DataStore<Preferences> {
    sharedDataStore?.let { return it }
    return synchronized(DATASTORE_NAME) {
        sharedDataStore ?: run {
            val appContext = applicationContext
            PreferenceDataStoreFactory.create(
                // A damaged file must not crash every collector, including the background wipe
                // observer. Presets and rotation counters live in it, so keep the damaged bytes.
                corruptionHandler = ReplaceFileCorruptionHandler {
                    backupCorruptedFile(appContext.preferencesDataStoreFile(DATASTORE_NAME))
                    emptyPreferences()
                },
                produceFile = { appContext.preferencesDataStoreFile(DATASTORE_NAME) }
            ).also { sharedDataStore = it }
        }
    }
}

private fun backupCorruptedFile(file: File) {
    runCatching {
        if (file.exists()) file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
    }
}

class PreferencesRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.starryPassDataStore())

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

    // An unreadable file (IOException) must not propagate to collectors, including the app-scoped
    // wipe observer that would crash the process. Fall back to defaults; they fail closed.
    private val safeData: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settingsFlow: Flow<AppSettings> = safeData.map { prefs ->
        AppSettings(
            theme = ThemePreference.fromKey(prefs[Keys.THEME]),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            flagSecure = prefs[Keys.FLAG_SECURE] ?: true,
            wipeOnBackground = prefs[Keys.WIPE_ON_BACKGROUND] ?: true,
            clipboardAutoClearSeconds = prefs[Keys.CLIPBOARD_AUTO_CLEAR_SEC] ?: 45,
            colorizePassword = prefs[Keys.COLORIZE_PASSWORD] ?: true
        )
    }

    // Unrelated setting changes re-emit the whole snapshot; only re-decode when the JSON changed.
    val presetsFlow: Flow<List<SitePreset>> = safeData
        .map { prefs -> prefs[Keys.SITE_PRESETS_JSON] }
        .distinctUntilChanged()
        .map { raw -> decodePresets(raw).sortedByDescending { it.updatedAtEpochMs } }

    suspend fun setTheme(theme: ThemePreference) {
        dataStore.edit { it[Keys.THEME] = theme.storageKey }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setFlagSecure(enabled: Boolean) {
        dataStore.edit { it[Keys.FLAG_SECURE] = enabled }
    }

    suspend fun setWipeOnBackground(enabled: Boolean) {
        dataStore.edit { it[Keys.WIPE_ON_BACKGROUND] = enabled }
    }

    suspend fun setClipboardAutoClearSeconds(seconds: Int) {
        dataStore.edit { it[Keys.CLIPBOARD_AUTO_CLEAR_SEC] = seconds.coerceAtLeast(0) }
    }

    suspend fun setColorizePassword(enabled: Boolean) {
        dataStore.edit { it[Keys.COLORIZE_PASSWORD] = enabled }
    }

    /** Full editor save: explicitly blank metadata must remain clearable. */
    suspend fun upsertPreset(preset: SitePreset) = savePreset(preset, policyOnly = false)

    /** Quick save: retain metadata from the current stored entry, not a stale UI snapshot. */
    suspend fun savePresetPolicy(preset: SitePreset) = savePreset(preset, policyOnly = true)

    private suspend fun savePreset(preset: SitePreset, policyOnly: Boolean) {
        val cleanCode = Spv2Spec.ecmaTrim(preset.code)
        if (cleanCode.isEmpty()) return
        val cleanId = Spv2Spec.ecmaTrim(preset.id).ifEmpty { UUID.randomUUID().toString() }
        val normalized = preset.copy(
            id = cleanId,
            code = cleanCode,
            alias = Spv2Spec.ecmaTrim(preset.alias),
            modeKey = preset.mode.key,
            length = Spv2Spec.clampLength(preset.length),
            counter = Spv2Spec.clampCounter(preset.counter),
            tierKey = preset.tier.key,
            autofillDomain = normalizeDomain(preset.autofillDomain),
            autofillPackage = Spv2Spec.ecmaTrim(preset.autofillPackage),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dataStore.edit { prefs ->
            val current = decodePresets(prefs[Keys.SITE_PRESETS_JSON]).toMutableList()
            val idIdx = current.indexOfFirst { it.id == normalized.id }
            val codeIdx = current.indexOfFirst { it.code == normalized.code }
            // Quick policy save always targets the site code; full editor keeps its own entry and replaces on rename.
            val idx = if (policyOnly) codeIdx else if (idIdx >= 0) idIdx else codeIdx
            if (idx >= 0) {
                current[idx] = if (policyOnly) {
                    current[idx].copy(
                        modeKey = normalized.modeKey,
                        length = normalized.length,
                        counter = normalized.counter,
                        tierKey = normalized.tierKey,
                        updatedAtEpochMs = normalized.updatedAtEpochMs
                    )
                } else {
                    normalized.copy(id = current[idx].id)
                }
                if (!policyOnly && codeIdx >= 0 && codeIdx != idx) current.removeAt(codeIdx)
            } else {
                current.add(0, normalized)
            }
            prefs[Keys.SITE_PRESETS_JSON] = json.encodeToString(current)
        }
    }

    suspend fun incrementPresetCounter(presetId: String): PresetRotationResult {
        val cleanId = Spv2Spec.ecmaTrim(presetId)
        if (cleanId.isEmpty()) return PresetRotationResult.NotFound
        var result: PresetRotationResult = PresetRotationResult.NotFound
        dataStore.edit { prefs ->
            val current = decodePresets(prefs[Keys.SITE_PRESETS_JSON]).toMutableList()
            val idx = current.indexOfFirst { it.id == cleanId }
            if (idx >= 0) {
                val item = current[idx]
                if (item.counter >= Spv2Spec.MAX_COUNTER) {
                    result = PresetRotationResult.LimitReached
                } else {
                    val next = item.copy(
                        counter = Spv2Spec.clampCounter(item.counter + 1),
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                    current[idx] = next
                    result = PresetRotationResult.Updated(next)
                    prefs[Keys.SITE_PRESETS_JSON] = json.encodeToString(current)
                }
            }
        }
        return result
    }

    suspend fun deletePreset(presetId: String) {
        val cleanId = Spv2Spec.ecmaTrim(presetId)
        if (cleanId.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decodePresets(prefs[Keys.SITE_PRESETS_JSON])
                .filterNot { it.id == cleanId }
            prefs[Keys.SITE_PRESETS_JSON] = json.encodeToString(current)
        }
    }

    suspend fun clearAllPresets() {
        dataStore.edit { prefs ->
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
        val normPkg = packageName?.let(Spv2Spec::ecmaTrim)?.takeIf { it.isNotEmpty() }

        if (normDomain != null) {
            // The most specific domain wins; recency (list order) only breaks ties.
            // Entries saved by older versions were normalised by older rules; normalise again.
            presets.mapNotNull { preset ->
                val pd = normalizeDomain(preset.autofillDomain)
                if (pd.isNotEmpty() && (normDomain == pd || normDomain.endsWith(".$pd"))) preset to pd else null
            }.maxByOrNull { (_, pd) -> pd.length }?.let { return it.first }
        }

        if (normPkg != null) {
            presets.firstOrNull { preset ->
                val pkg = Spv2Spec.ecmaTrim(preset.autofillPackage)
                pkg.isNotEmpty() && pkg.equals(normPkg, ignoreCase = true)
            }?.let { return it }
        }

        if (normDomain != null) {
            presets.firstOrNull { preset ->
                preset.code.equals(normDomain, ignoreCase = true)
            }?.let { return it }

            presets.mapNotNull { preset ->
                val codeDomain = normalizeCodeAsDomain(preset.code)
                if (codeDomain.isNotEmpty() &&
                    (normDomain == codeDomain || (codeDomain.contains('.') && normDomain.endsWith(".$codeDomain")))
                ) {
                    preset to codeDomain
                } else {
                    null
                }
            }.maxByOrNull { (_, codeDomain) -> codeDomain.length }?.let { return it.first }

            val domainStem = extractDomainStem(normDomain)
            if (domainStem.isNotEmpty()) {
                presets.firstOrNull { preset ->
                    preset.code.equals(domainStem, ignoreCase = true)
                }?.let { return it }
            }
        }

        if (normPkg != null) {
            val pkgStem = suggestCodeFromContext(null, normPkg)
            if (pkgStem.isNotEmpty()) {
                presets.firstOrNull { preset ->
                    preset.code.equals(pkgStem, ignoreCase = true)
                }?.let { return it }
            }
        }

        return null
    }

    /**
     * Decodes entries one by one: a single unreadable preset must not hide the others, nor make
     * the next save overwrite the whole stored list with only the new entry.
     */
    private fun decodePresets(raw: String?): List<SitePreset> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return emptyList()
        val seenIds = HashSet<String>()
        val seenCodes = HashSet<String>()
        return array.mapNotNull { element ->
            val decoded = runCatching {
                json.decodeFromJsonElement(SitePreset.serializer(), element)
            }.getOrNull() ?: return@mapNotNull null
            val cleanCode = Spv2Spec.ecmaTrim(decoded.code)
            if (cleanCode.isEmpty()) return@mapNotNull null
            val cleanId = Spv2Spec.ecmaTrim(decoded.id).ifEmpty {
                UUID.nameUUIDFromBytes(cleanCode.toByteArray(Charsets.UTF_8)).toString()
            }
            // Check both before recording either, so a rejected entry cannot reserve its id.
            if (cleanId in seenIds || cleanCode in seenCodes) return@mapNotNull null
            seenIds.add(cleanId)
            seenCodes.add(cleanCode)
            decoded.copy(
                id = cleanId,
                code = cleanCode,
                alias = Spv2Spec.ecmaTrim(decoded.alias),
                modeKey = decoded.mode.key,
                length = Spv2Spec.clampLength(decoded.length),
                counter = Spv2Spec.clampCounter(decoded.counter),
                tierKey = decoded.tier.key,
                autofillDomain = normalizeDomain(decoded.autofillDomain),
                autofillPackage = Spv2Spec.ecmaTrim(decoded.autofillPackage)
            )
        }
    }

    companion object {
        private val MULTI_PART_TLDS = setOf(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk",
            "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
            "com.hk", "org.hk", "com.tw", "org.tw", "com.sg", "com.my",
            "co.jp", "ne.jp", "or.jp", "ac.jp", "co.kr", "or.kr",
            "com.au", "net.au", "org.au", "edu.au", "co.nz",
            "co.in", "co.id", "co.th", "com.vn", "com.ph", "com.pk",
            "com.br", "com.mx", "com.ar", "com.co", "com.tr", "com.ua",
            "co.za", "com.eg", "com.sa"
        )

        // Second-level labels that country registries commonly delegate (`example.org.nz`,
        // `example.gov.in`); this covers suffixes missing from the explicit list above.
        private val GENERIC_SECOND_LEVEL = setOf(
            "ac", "co", "com", "edu", "go", "gob", "gov", "mil", "ne", "net", "or", "org",
            "ltd", "plc", "sch"
        )

        private val IPV4_ADDRESS = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val WRAPPER_SCHEME_PREFIX = Regex("^(?:blob|view-source):")
        private val SCHEME_PREFIX = Regex("^[a-z][a-z0-9+.-]*://")
        // Opaque and local/non-network URIs carry no network host; without this, `tel:12345` reads as
        // host:port, `mailto:a@evil.com` / `cid:x@evil.com` as `evil.com`, and `content://` as a host.
        private val NON_NETWORK_URI = Regex(
            "^(?:mailto|tel|sms|smsto|mms|mmsto|voicemail|cid|mid|geo|urn|javascript|about|data|file|content|intent|market|android-app):"
        )
        private val HEX_GROUP = Regex("^[0-9a-f]{1,4}$")
        private val PORT_DIGITS = Regex("^\\d{1,5}$")

        private fun isTwoPartPublicSuffix(secondLevel: String, tld: String): Boolean =
            "$secondLevel.$tld" in MULTI_PART_TLDS ||
                (tld.length == 2 && secondLevel in GENERIC_SECOND_LEVEL)

        private fun isValidPort(rawPort: String): Boolean {
            if (!PORT_DIGITS.matches(rawPort)) return false
            val port = rawPort.toIntOrNull() ?: return false
            return port in 1..65535
        }

        private fun isValidIpv4Literal(host: String): Boolean {
            if (!IPV4_ADDRESS.matches(host)) return false
            return host.split('.').all { part ->
                val octet = part.toIntOrNull() ?: return@all false
                octet in 0..255
            }
        }

        private fun isValidIpv6Literal(host: String): Boolean {
            if (!host.contains(':') || host.contains(":::")) return false
            if ((host.startsWith(":") && !host.startsWith("::")) ||
                (host.endsWith(":") && !host.endsWith("::"))
            ) {
                return false
            }
            val doubleColonParts = host.split("::")
            if (doubleColonParts.size > 2) return false
            val hasCompression = doubleColonParts.size == 2
            val left = if (doubleColonParts[0].isEmpty()) emptyList() else doubleColonParts[0].split(':')
            val right = if (hasCompression && doubleColonParts[1].isEmpty()) {
                emptyList()
            } else if (hasCompression) {
                doubleColonParts[1].split(':')
            } else {
                emptyList()
            }
            val segments = left + right
            var groupCount = 0
            for (i in segments.indices) {
                val seg = segments[i]
                if (seg.contains('.')) {
                    if (i != segments.lastIndex || !isValidIpv4Literal(seg)) return false
                    groupCount += 2
                } else {
                    if (!HEX_GROUP.matches(seg)) return false
                    groupCount += 1
                }
            }
            return if (hasCompression) groupCount in 1..7 || host == "::" else groupCount == 8
        }

        private fun normalizeCodeAsDomain(rawCode: String): String {
            val trimmed = Spv2Spec.ecmaTrim(rawCode).lowercase(Locale.ROOT)
            if (trimmed.isEmpty()) return ""
            // A scheme-less site code containing '/', '\', '?', '#', or '@' (e.g. `github.com/token`
            // or `alice@example.com`) is a purpose-qualified code, not a bare domain.
            if (!WRAPPER_SCHEME_PREFIX.containsMatchIn(trimmed) &&
                !SCHEME_PREFIX.containsMatchIn(trimmed) &&
                !trimmed.startsWith("//") &&
                trimmed.indexOfAny(charArrayOf('/', '\\', '?', '#', '@')) >= 0
            ) {
                return ""
            }
            return normalizeDomain(trimmed)
        }

        fun normalizeDomain(raw: String): String {
            var s = Spv2Spec.ecmaTrim(raw).lowercase(Locale.ROOT)
            while (WRAPPER_SCHEME_PREFIX.containsMatchIn(s)) {
                s = Spv2Spec.ecmaTrim(s.replaceFirst(WRAPPER_SCHEME_PREFIX, ""))
            }
            if (NON_NETWORK_URI.containsMatchIn(s)) return ""
            s = s.replace(SCHEME_PREFIX, "").removePrefix("//")
            val end = s.indexOfAny(charArrayOf('/', '\\', '?', '#'))
            if (end >= 0) s = s.substring(0, end)
            s = Spv2Spec.ecmaTrim(s.substringAfterLast('@'))
            if (s.startsWith("[")) {
                // IPv6 literal: its colons are part of the host, only a port may follow the bracket.
                val close = s.indexOf(']')
                if (close <= 1) return ""
                val host = Spv2Spec.ecmaTrim(s.substring(1, close))
                val remainder = Spv2Spec.ecmaTrim(s.substring(close + 1))
                if (!isValidIpv6Literal(host)) return ""
                if (remainder.isNotEmpty()) {
                    if (!remainder.startsWith(":") || !isValidPort(Spv2Spec.ecmaTrim(remainder.substring(1)))) {
                        return ""
                    }
                }
                s = "[$host]"
            } else {
                val firstColon = s.indexOf(':')
                if (firstColon >= 0) {
                    if (firstColon != s.lastIndexOf(':')) {
                        // Unbracketed IPv6 literal (e.g. `2001:db8::1` or `::1`): canonicalise with brackets.
                        val host = Spv2Spec.ecmaTrim(s)
                        if (!isValidIpv6Literal(host)) return ""
                        s = "[$host]"
                    } else {
                        val portPart = Spv2Spec.ecmaTrim(s.substring(firstColon + 1))
                        if (!isValidPort(portPart)) return ""
                        s = Spv2Spec.ecmaTrim(s.substring(0, firstColon))
                    }
                }
            }
            // Cookie-style entries (`.example.com`, `*.example.com`) already match by suffix.
            while (s.startsWith(".") || s.startsWith("*.")) {
                s = s.removePrefix("*.").trimStart('.')
            }
            if (s == "www." || s.startsWith("www..")) return ""
            s = s.trimEnd('.')
            if (s.isEmpty() || s == "*" || (!s.startsWith("[") && s.contains(".."))) return ""
            if (s.any { it <= ' ' || Spv2Spec.ecmaTrim(it.toString()).isEmpty() }) return ""
            if (!s.startsWith("[")) {
                if (s.indexOfAny(charArrayOf('[', ']', '*')) >= 0) return ""
                val labels = s.split('.')
                if (labels.any { it.isEmpty() || it.startsWith('-') || it.endsWith('-') }) return ""
                // Digits and dots only is an IPv4 literal; anything else (`1`, `1.2`, `999.1.1.1`) is not a host.
                if (s.all { it in '0'..'9' || it == '.' } && !isValidIpv4Literal(s)) return ""
            }
            if (s.startsWith("www.")) {
                val withoutWww = s.removePrefix("www.").trimStart('.')
                val parts = withoutWww.split('.').filter { it.isNotEmpty() }
                if (parts.size >= 3 || (parts.size == 2 && !isTwoPartPublicSuffix(parts[0], parts[1]))) {
                    s = withoutWww
                }
            }
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
                val rawParts = Spv2Spec.ecmaTrim(packageName).lowercase(Locale.ROOT).split('.').filter { it.isNotEmpty() }
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
            // An IP address has no registrable name; its last octet would be a meaningless code.
            if (isValidIpv4Literal(clean) || clean.startsWith("[")) return clean
            val parts = clean.split('.').filter { it.isNotEmpty() }
            if (parts.size <= 1) return clean
            val multiPartSuffix = parts.size >= 3 &&
                isTwoPartPublicSuffix(parts[parts.size - 2], parts.last())
            return if (multiPartSuffix) {
                parts[parts.size - 3]
            } else {
                parts[parts.size - 2]
            }
        }
    }
}
