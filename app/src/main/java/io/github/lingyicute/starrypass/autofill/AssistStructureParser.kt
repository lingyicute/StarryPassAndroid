/*
 * StarryPass Android — AssistStructure Field & Domain Parser (Native + WebView + Compose)
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import io.github.lingyicute.starrypass.crypto.Spv2Spec

internal data class ParsedAutofillStructure(
    val passwordIds: List<AutofillId>,
    val focusedEditableId: AutofillId?,
    val allEditableIds: List<AutofillId>,
    val webDomain: String?,
    val packageName: String?
) {
    /**
     * Resolves which fields should be targeted for filling:
     * 1. Explicit password fields when present (unless manual autofill was invoked on another focused editable input).
     * 2. Focused editable field (or all editable text fields) when manually requested via context menu.
     */
    fun resolveTargetIds(isManualRequest: Boolean): List<AutofillId> {
        if (isManualRequest && focusedEditableId != null && focusedEditableId !in passwordIds) {
            return listOf(focusedEditableId)
        }
        if (passwordIds.isNotEmpty()) {
            return passwordIds.distinct()
        }
        if (isManualRequest) {
            if (focusedEditableId != null) return listOf(focusedEditableId)
            if (allEditableIds.isNotEmpty()) return allEditableIds.distinct()
        }
        return emptyList()
    }
}

internal object AssistStructureParser {

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

    fun parse(structure: AssistStructure): ParsedAutofillStructure {
        val passwordIds = mutableListOf<AutofillId>()
        val allEditableIds = mutableListOf<AutofillId>()
        var focusedEditableId: AutofillId? = null
        var detectedDomain: String? = null
        val pkgName = structure.activityComponent?.packageName

        var focusedDomain: String? = null
        var passwordDomain: String? = null

        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i).rootViewNode
            traverseNode(
                node = root,
                inheritedDomain = null,
                onDomain = { dom -> if (detectedDomain == null) detectedDomain = dom },
                onFocusedEditable = { id, dom ->
                    if (focusedEditableId == null) {
                        focusedEditableId = id
                        if (dom != null) focusedDomain = dom
                    }
                },
                onPasswordDomain = { dom -> if (passwordDomain == null && dom != null) passwordDomain = dom },
                passwordIds = passwordIds,
                allEditableIds = allEditableIds
            )
        }

        return ParsedAutofillStructure(
            passwordIds = passwordIds.distinct(),
            focusedEditableId = focusedEditableId,
            allEditableIds = allEditableIds.distinct(),
            webDomain = focusedDomain ?: passwordDomain ?: detectedDomain,
            packageName = pkgName
        )
    }

    private fun traverseNode(
        node: AssistStructure.ViewNode,
        inheritedDomain: String?,
        onDomain: (String) -> Unit,
        onFocusedEditable: (AutofillId, String?) -> Unit,
        onPasswordDomain: (String?) -> Unit,
        passwordIds: MutableList<AutofillId>,
        allEditableIds: MutableList<AutofillId>
    ) {
        val dom = node.webDomain?.let(Spv2Spec::ecmaTrim)?.takeIf { it.isNotEmpty() }
        if (dom != null) {
            onDomain(dom)
        }
        val activeDomain = dom ?: inheritedDomain

        val autofillId = node.autofillId
        if (autofillId != null && isFillableTextInput(node)) {
            allEditableIds.add(autofillId)
            if (node.isFocused) {
                onFocusedEditable(autofillId, activeDomain)
            }
            if (isPasswordNode(node)) {
                passwordIds.add(autofillId)
                onPasswordDomain(activeDomain)
            }
        }

        for (c in 0 until node.childCount) {
            traverseNode(
                node = node.getChildAt(c),
                inheritedDomain = activeDomain,
                onDomain = onDomain,
                onFocusedEditable = onFocusedEditable,
                onPasswordDomain = onPasswordDomain,
                passwordIds = passwordIds,
                allEditableIds = allEditableIds
            )
        }
    }

    private fun isFillableTextInput(node: AssistStructure.ViewNode): Boolean {
        // Inspect HTML tag first so <input type="hidden|submit|button|..."> or <select>/<button>
        // is never treated as text input even when a WebView reports autofillType == AUTOFILL_TYPE_TEXT.
        val htmlTag = node.htmlInfo?.tag?.let(Spv2Spec::ecmaTrim)
        if (!htmlTag.isNullOrEmpty()) {
            if (htmlTag.equals("input", ignoreCase = true)) {
                val typeAttr = node.htmlInfo?.attributes
                    ?.firstOrNull { it.first?.let(Spv2Spec::ecmaTrim)?.equals("type", ignoreCase = true) == true }
                    ?.second
                    ?.let(Spv2Spec::ecmaTrim)
                    ?.lowercase()
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
            val cleanHints = hints.mapNotNull { it?.let(Spv2Spec::ecmaTrim)?.takeIf(String::isNotEmpty) }
            if (cleanHints.any { hint ->
                    hint.equals(View.AUTOFILL_HINT_PASSWORD, ignoreCase = true) ||
                        hint.contains("password", ignoreCase = true) ||
                        hint.equals("newPassword", ignoreCase = true)
                }
            ) {
                return true
            }
            if (cleanHints.any { hint ->
                    hint.equals(View.AUTOFILL_HINT_USERNAME, ignoreCase = true) ||
                        hint.equals(View.AUTOFILL_HINT_EMAIL_ADDRESS, ignoreCase = true) ||
                        hint.equals("email", ignoreCase = true) ||
                        hint.equals(View.AUTOFILL_HINT_PHONE, ignoreCase = true) ||
                        hint.equals(View.AUTOFILL_HINT_NAME, ignoreCase = true) ||
                        hint.equals("one-time-code", ignoreCase = true) ||
                        hint.contains("otp", ignoreCase = true)
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
                val attrName = attr.first?.let(Spv2Spec::ecmaTrim)?.lowercase() ?: continue
                val attrVal = attr.second?.let(Spv2Spec::ecmaTrim)?.lowercase() ?: continue
                if (attrName == "type") {
                    if (attrVal == "password") return true
                    if (attrVal in NON_PASSWORD_HTML_TYPES) explicitNonPasswordHtml = true
                }
                if (attrName == "autocomplete") {
                    if (attrVal.contains("password")) return true
                    val tokens = attrVal.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    if (tokens.any { it in NON_PASSWORD_AUTOCOMPLETE_TOKENS }) {
                        explicitNonPasswordHtml = true
                    }
                }
                if ((attrName == "name" || attrName == "id" || attrName == "placeholder") &&
                    PASSWORD_KEYWORDS.any { kw -> attrVal.contains(kw) }
                ) {
                    htmlKeywordMatched = true
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
            if (klass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            ) {
                return true
            }
        }

        // 4. Resource idEntry, hint text, or contentDescription heuristics
        val idEntry = node.idEntry?.lowercase().orEmpty()
        val hintText = node.hint?.lowercase().orEmpty()
        val contentDesc = node.contentDescription?.toString()?.lowercase().orEmpty()
        if (PASSWORD_KEYWORDS.any { kw ->
                idEntry.contains(kw) || hintText.contains(kw) || contentDesc.contains(kw)
            }
        ) {
            return true
        }

        return false
    }
}
