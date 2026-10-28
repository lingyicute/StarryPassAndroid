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

    fun parse(structure: AssistStructure): ParsedAutofillStructure {
        val passwordIds = mutableListOf<AutofillId>()
        val allEditableIds = mutableListOf<AutofillId>()
        var focusedEditableId: AutofillId? = null
        var detectedDomain: String? = null
        val pkgName = structure.activityComponent?.packageName

        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i).rootViewNode
            traverseNode(
                node = root,
                onDomain = { dom -> if (detectedDomain == null) detectedDomain = dom },
                onFocusedEditable = { id -> if (focusedEditableId == null) focusedEditableId = id },
                passwordIds = passwordIds,
                allEditableIds = allEditableIds
            )
        }

        return ParsedAutofillStructure(
            passwordIds = passwordIds.distinct(),
            focusedEditableId = focusedEditableId,
            allEditableIds = allEditableIds.distinct(),
            webDomain = detectedDomain,
            packageName = pkgName
        )
    }

    private fun traverseNode(
        node: AssistStructure.ViewNode,
        onDomain: (String) -> Unit,
        onFocusedEditable: (AutofillId) -> Unit,
        passwordIds: MutableList<AutofillId>,
        allEditableIds: MutableList<AutofillId>
    ) {
        val dom = node.webDomain
        if (!dom.isNullOrBlank()) {
            onDomain(dom)
        }

        val autofillId = node.autofillId
        if (autofillId != null && isFillableTextInput(node)) {
            allEditableIds.add(autofillId)
            if (node.isFocused) {
                onFocusedEditable(autofillId)
            }
            if (isPasswordNode(node)) {
                passwordIds.add(autofillId)
            }
        }

        for (c in 0 until node.childCount) {
            traverseNode(
                node = node.getChildAt(c),
                onDomain = onDomain,
                onFocusedEditable = onFocusedEditable,
                passwordIds = passwordIds,
                allEditableIds = allEditableIds
            )
        }
    }

    private fun isFillableTextInput(node: AssistStructure.ViewNode): Boolean {
        if (node.autofillType == View.AUTOFILL_TYPE_TEXT) return true
        // Some WebViews / hybrid engines expose <input> via htmlInfo while leaving autofillType == NONE
        val htmlTag = node.htmlInfo?.tag
        if (htmlTag != null && htmlTag.equals("input", ignoreCase = true)) {
            val typeAttr = node.htmlInfo?.attributes
                ?.firstOrNull { it.first.equals("type", ignoreCase = true) }
                ?.second
                ?.lowercase()
            return typeAttr == null || typeAttr in setOf("password", "text", "tel", "number", "email", "search")
        }
        val className = node.className.orEmpty()
        return node.autofillType != View.AUTOFILL_TYPE_NONE &&
            (className.contains("EditText", ignoreCase = true) || node.inputType != 0)
    }

    private fun isPasswordNode(node: AssistStructure.ViewNode): Boolean {
        // 1. Explicit Android autofillHints
        val hints = node.autofillHints
        if (!hints.isNullOrEmpty() && hints.any { hint ->
                hint == View.AUTOFILL_HINT_PASSWORD ||
                    hint.contains("password", ignoreCase = true) ||
                    hint.equals("newPassword", ignoreCase = true)
            }
        ) {
            return true
        }

        // 2. HTML <input> attributes in browsers / WebViews
        val htmlAttrs = node.htmlInfo?.attributes
        if (htmlAttrs != null) {
            for (attr in htmlAttrs) {
                val attrName = attr.first?.lowercase() ?: continue
                val attrVal = attr.second?.lowercase() ?: continue
                if (attrName == "type" && attrVal == "password") return true
                if (attrName == "autocomplete" && attrVal.contains("password")) return true
                if ((attrName == "name" || attrName == "id" || attrName == "placeholder") &&
                    PASSWORD_KEYWORDS.any { kw -> attrVal.contains(kw) }
                ) {
                    return true
                }
            }
        }

        // 3. Android InputType flags (covers hidden & visible password toggles and numeric PIN inputs)
        val inputType = node.inputType
        if (inputType != 0) {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val klass = inputType and InputType.TYPE_MASK_CLASS
            if (klass == InputType.TYPE_CLASS_TEXT && (
                    variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    )
            ) {
                return true
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
