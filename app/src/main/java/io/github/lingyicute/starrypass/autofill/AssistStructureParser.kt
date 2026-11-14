/*
 * StarryPass Android — AssistStructure Field & Domain Parser (Native + WebView + Compose)
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.autofill

import android.app.assist.AssistStructure
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import io.github.lingyicute.starrypass.crypto.Spv2Spec
import io.github.lingyicute.starrypass.data.PreferencesRepository
import java.util.Locale

internal data class ParsedAutofillStructure(
    val passwordIds: List<AutofillId>,
    val focusedEditableId: AutofillId?,
    val webDomain: String?,
    val packageName: String?
) {
    /**
     * Resolves which fields should be targeted for filling:
     * 1. Explicit password fields when present (unless manual autofill was invoked on another focused editable input).
     * 2. Only the focused editable field when manually requested outside a password field.
     * A missing focus must not turn a password into the value of every text input on the page.
     */
    fun resolveTargetIds(isManualRequest: Boolean): List<AutofillId> =
        AssistStructureParser.selectTargetIds(passwordIds, focusedEditableId, isManualRequest)
}

internal object AssistStructureParser {

    // Compiled once: the parser visits every node of a possibly large view tree.
    private val WHITESPACE_RUN = Regex("\\s+")
    private val WEB_SCHEMES = setOf("http", "https")
    private val WRAPPER_SCHEME_PREFIX = Regex("^(?:blob|view-source):", RegexOption.IGNORE_CASE)
    private val EMBEDDED_SCHEME_PREFIX = Regex("^([a-z][a-z0-9+.-]*)://", RegexOption.IGNORE_CASE)

    private val PASSWORD_KEYWORDS = listOf(
        "password",
        "passwd",
        "passcode",
        "pwd",
        "user_pass",
        "current-password",
        "new-password",
        "密码",
        "口令"
    )

    private val HTML_TEXT_INPUT_TYPES = setOf(
        "password",
        "text",
        "tel",
        "number",
        "email",
        "search",
        "url"
    )

    private val NON_PASSWORD_HTML_TYPES = setOf(
        "email",
        "search",
        "tel",
        "url"
    )

    private val NON_PASSWORD_AUTOCOMPLETE_TOKENS = setOf(
        "username",
        "email",
        "tel",
        "url",
        "name",
        "given-name",
        "family-name",
        "one-time-code"
    )

    private val NON_PASSWORD_FIELD_KEYWORDS = listOf(
        "username",
        "user_name",
        "email",
        "e-mail",
        "phone",
        "mobile",
        "otp",
        "one-time",
        "verification",
        "captcha",
        "用户名",
        "账号",
        "邮箱",
        "手机",
        "验证码"
    )

    fun parse(structure: AssistStructure): ParsedAutofillStructure {
        val passwordFields = mutableListOf<Pair<AutofillId, String?>>()
        val editableFields = mutableListOf<Pair<AutofillId, String?>>()
        var focusedEditableId: AutofillId? = null
        var detectedDomain: String? = null
        val pkgName = structure.activityComponent?.packageName

        var focusedPasswordDomain: String? = null
        var focusedDomain: String? = null
        var passwordDomain: String? = null

        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i).rootViewNode
            traverseNode(
                node = root,
                inheritedOrigin = NodeOrigin.Native,
                onDomain = { dom -> if (detectedDomain == null) detectedDomain = dom },
                onFocusedEditable = { id, dom ->
                    if (focusedEditableId == null) {
                        focusedEditableId = id
                        if (dom != null) focusedDomain = dom
                    }
                },
                onPasswordDomain = { dom, isFocused ->
                    if (dom != null) {
                        if (passwordDomain == null) passwordDomain = dom
                        if (isFocused && focusedPasswordDomain == null) focusedPasswordDomain = dom
                    }
                },
                passwordFields = passwordFields,
                editableFields = editableFields
            )
        }

        val webDomain = focusedPasswordDomain ?: focusedDomain ?: passwordDomain ?: detectedDomain
        return ParsedAutofillStructure(
            passwordIds = idsWithinDomain(passwordFields, webDomain),
            focusedEditableId = focusedEditableId?.takeIf { it in idsWithinDomain(editableFields, webDomain) },
            webDomain = webDomain,
            packageName = pkgName
        )
    }

    internal sealed interface NodeOrigin {
        data object Native : NodeOrigin
        data class Verified(val domain: String) : NodeOrigin
        data object UntrustedWeb : NodeOrigin
    }

    /**
     * Nodes without their own `webDomain` or `webScheme` inherit [inherited]; nodes that
     * explicitly declare an untrusted scheme or invalid host become [NodeOrigin.UntrustedWeb]
     * so they neither inherit a parent frame's domain nor pose as native app fields.
     */
    internal fun resolveEffectiveOrigin(
        rawDomain: String?,
        rawScheme: String?,
        inherited: NodeOrigin
    ): NodeOrigin {
        val cleanDomain = rawDomain?.let(Spv2Spec::ecmaTrim)?.takeIf { it.isNotEmpty() }
        val cleanScheme = rawScheme?.let(Spv2Spec::ecmaTrim)?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        if (cleanDomain == null && cleanScheme == null) return inherited
        val resolved = resolveNodeDomain(cleanDomain, cleanScheme)
        return if (resolved != null) NodeOrigin.Verified(resolved) else NodeOrigin.UntrustedWeb
    }

    /**
     * Verifies that the live [AssistStructure] attached to [AutofillAuthActivity] still belongs
     * to the same origin and package as the initial fill request, and never falls back to stale
     * field IDs when the live structure parsed cleanly with zero eligible targets.
     */
    internal fun <T> resolveAuthenticatedTargets(
        hasLiveStructure: Boolean,
        liveParseFailed: Boolean,
        liveTargets: List<T>,
        liveWebDomain: String?,
        livePackageName: String?,
        expectedWebDomain: String?,
        expectedPackageName: String?,
        selfPackageName: String,
        fallbackTargets: List<T>
    ): List<T> {
        if (!hasLiveStructure || liveParseFailed) {
            if (expectedPackageName == selfPackageName) return emptyList()
            return fallbackTargets
        }
        if (livePackageName == selfPackageName) return emptyList()
        if (livePackageName != expectedPackageName || liveWebDomain != expectedWebDomain) {
            return emptyList()
        }
        return liveTargets
    }

    /**
     * A page can embed frames from other sites. A field that explicitly belongs to another
     * domain than the one the password is derived for must never receive that password.
     */
    internal fun <T> idsWithinDomain(fields: List<Pair<T, String?>>, webDomain: String?): List<T> =
        fields.filter { (_, domain) -> domain == null || domain == webDomain }
            .map { it.first }
            .distinct()

    internal fun <T> selectTargetIds(
        passwordIds: List<T>,
        focusedEditableId: T?,
        isManualRequest: Boolean
    ): List<T> {
        if (isManualRequest && focusedEditableId != null && focusedEditableId !in passwordIds) {
            return listOf(focusedEditableId)
        }
        // Without a focused editable field, only explicitly identified password fields are safe.
        return passwordIds.distinct()
    }

    internal fun resolveNodeDomain(rawDomain: String?, rawScheme: String?): String? {
        val cleanDomain = rawDomain?.let(Spv2Spec::ecmaTrim)?.takeIf { it.isNotEmpty() } ?: return null
        val cleanScheme = rawScheme?.let(Spv2Spec::ecmaTrim)?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        // webScheme is authoritative even when webDomain happens to contain its own URL scheme.
        if (cleanScheme != null && cleanScheme !in WEB_SCHEMES) return null
        if (WRAPPER_SCHEME_PREFIX.containsMatchIn(cleanDomain)) return null
        val embeddedSchemeMatch = EMBEDDED_SCHEME_PREFIX.find(cleanDomain)
        if (embeddedSchemeMatch != null) {
            val embeddedScheme = embeddedSchemeMatch.groupValues[1].lowercase(Locale.ROOT)
            if (embeddedScheme !in WEB_SCHEMES) return null
            if (cleanScheme != null && embeddedScheme != cleanScheme) return null
        } else {
            val schemeSep = cleanDomain.indexOf("://")
            val firstDelim = cleanDomain.indexOfAny(charArrayOf('/', '\\', '?', '#'))
            if (schemeSep >= 0 && (firstDelim < 0 || schemeSep < firstDelim)) return null
            if (cleanDomain.startsWith("http:", ignoreCase = true) ||
                cleanDomain.startsWith("https:", ignoreCase = true)
            ) {
                return null
            }
        }
        return PreferencesRepository.normalizeDomain(cleanDomain).takeIf { it.isNotEmpty() }
    }

    private fun traverseNode(
        node: AssistStructure.ViewNode,
        inheritedOrigin: NodeOrigin,
        onDomain: (String) -> Unit,
        onFocusedEditable: (AutofillId, String?) -> Unit,
        onPasswordDomain: (String?, Boolean) -> Unit,
        passwordFields: MutableList<Pair<AutofillId, String?>>,
        editableFields: MutableList<Pair<AutofillId, String?>>
    ) {
        val rawScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.webScheme else null
        val activeOrigin = resolveEffectiveOrigin(node.webDomain, rawScheme, inheritedOrigin)
        if (activeOrigin is NodeOrigin.Verified) {
            onDomain(activeOrigin.domain)
        }

        val autofillId = node.autofillId
        if (autofillId != null && activeOrigin !is NodeOrigin.UntrustedWeb && isFillableTextInput(node)) {
            val activeDomain = (activeOrigin as? NodeOrigin.Verified)?.domain
            editableFields.add(autofillId to activeDomain)
            if (node.isFocused) {
                onFocusedEditable(autofillId, activeDomain)
            }
            if (isPasswordNode(node)) {
                passwordFields.add(autofillId to activeDomain)
                onPasswordDomain(activeDomain, node.isFocused)
            }
        }

        for (c in 0 until node.childCount) {
            traverseNode(
                node = node.getChildAt(c),
                inheritedOrigin = activeOrigin,
                onDomain = onDomain,
                onFocusedEditable = onFocusedEditable,
                onPasswordDomain = onPasswordDomain,
                passwordFields = passwordFields,
                editableFields = editableFields
            )
        }
    }

    private fun isFillableTextInput(node: AssistStructure.ViewNode): Boolean {
        // Inspect HTML tag first so <input type="hidden|submit|button|..."> or <select>/<button>
        // is never treated as text input even when a WebView reports autofillType == AUTOFILL_TYPE_TEXT.
        val htmlTag = node.htmlInfo?.tag?.let(Spv2Spec::ecmaTrim)
        if (!htmlTag.isNullOrEmpty()) {
            val attrs = node.htmlInfo?.attributes
            if (attrs?.any { attr ->
                    val name = attr.first?.let(Spv2Spec::ecmaTrim)?.lowercase(Locale.ROOT)
                    val value = attr.second?.let(Spv2Spec::ecmaTrim)?.lowercase(Locale.ROOT)
                    (name == "disabled" || name == "readonly") && value != "false"
                } == true
            ) {
                return false
            }
            if (htmlTag.equals("input", ignoreCase = true)) {
                val typeAttr = attrs
                    ?.firstOrNull { it.first?.let(Spv2Spec::ecmaTrim)?.equals("type", ignoreCase = true) == true }
                    ?.second
                    ?.let(Spv2Spec::ecmaTrim)
                    ?.lowercase(Locale.ROOT)
                return typeAttr.isNullOrEmpty() || typeAttr in HTML_TEXT_INPUT_TYPES
            }
            return htmlTag.equals("textarea", ignoreCase = true)
        }
        return node.autofillType == View.AUTOFILL_TYPE_TEXT
    }

    private fun isPasswordNode(node: AssistStructure.ViewNode): Boolean {
        // 1. Explicit Android autofillHints
        val hints = node.autofillHints
        if (!hints.isNullOrEmpty()) {
            val cleanHints = hints.mapNotNull {
                it?.let(Spv2Spec::ecmaTrim)?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
            }
            if (cleanHints.any { hint ->
                    hint == View.AUTOFILL_HINT_PASSWORD.lowercase(Locale.ROOT) ||
                        hint.contains("password") ||
                        hint == "newpassword"
                }
            ) {
                return true
            }
            if (cleanHints.any { hint ->
                    hint == View.AUTOFILL_HINT_USERNAME.lowercase(Locale.ROOT) ||
                        hint.contains("username") ||
                        hint == View.AUTOFILL_HINT_EMAIL_ADDRESS.lowercase(Locale.ROOT) ||
                        hint.contains("email") ||
                        hint == View.AUTOFILL_HINT_PHONE.lowercase(Locale.ROOT) ||
                        hint.contains("phone") ||
                        hint == View.AUTOFILL_HINT_NAME.lowercase(Locale.ROOT) ||
                        hint == "one-time-code" ||
                        hint.contains("otp")
                }
            ) {
                return false
            }
        }

        // 2. HTML <input> attributes in browsers / WebViews
        var explicitNonPasswordHtml = false
        var htmlKeywordMatched = false
        val htmlAttrs = node.htmlInfo?.attributes
        if (htmlAttrs != null) {
            for (attr in htmlAttrs) {
                val attrName = attr.first?.let(Spv2Spec::ecmaTrim)?.lowercase(Locale.ROOT) ?: continue
                val attrVal = attr.second?.let(Spv2Spec::ecmaTrim)?.lowercase(Locale.ROOT) ?: continue
                if (attrName == "type") {
                    if (attrVal == "password") return true
                    if (attrVal in NON_PASSWORD_HTML_TYPES) explicitNonPasswordHtml = true
                }
                if (attrName == "autocomplete") {
                    if (attrVal.contains("password")) return true
                    val tokens = attrVal.split(WHITESPACE_RUN).filter { it.isNotEmpty() }
                    if (tokens.any { it in NON_PASSWORD_AUTOCOMPLETE_TOKENS }) {
                        explicitNonPasswordHtml = true
                    }
                }
                if (attrName == "name" || attrName == "id" || attrName == "placeholder" || attrName == "aria-label") {
                    if (NON_PASSWORD_FIELD_KEYWORDS.any { kw -> attrVal.contains(kw) }) {
                        explicitNonPasswordHtml = true
                    } else if (PASSWORD_KEYWORDS.any { kw -> attrVal.contains(kw) }) {
                        htmlKeywordMatched = true
                    }
                }
            }
        }
        if (explicitNonPasswordHtml) return false
        if (htmlKeywordMatched) return true

        // 3. Android InputType flags (covers hidden & visible password toggles and numeric PIN inputs)
        val inputType = node.inputType
        if (inputType != 0) {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val klass = inputType and InputType.TYPE_MASK_CLASS
            if (klass == InputType.TYPE_CLASS_TEXT) {
                if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                ) {
                    return true
                }
                if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_URI ||
                    variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                ) {
                    return false
                }
            }
            if (klass == InputType.TYPE_CLASS_PHONE || klass == InputType.TYPE_CLASS_DATETIME) {
                return false
            }
            if (klass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            ) {
                return true
            }
        }

        // 4. Resource idEntry, hint text, or contentDescription heuristics
        val idEntry = node.idEntry?.lowercase(Locale.ROOT).orEmpty()
        val hintText = node.hint?.lowercase(Locale.ROOT).orEmpty()
        val contentDesc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
        if (NON_PASSWORD_FIELD_KEYWORDS.any { kw ->
                idEntry.contains(kw) || hintText.contains(kw) || contentDesc.contains(kw)
            }
        ) {
            return false
        }
        if (PASSWORD_KEYWORDS.any { kw ->
                idEntry.contains(kw) || hintText.contains(kw) || contentDesc.contains(kw)
            }
        ) {
            return true
        }

        return false
    }
}
