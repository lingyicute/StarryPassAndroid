/*
 * StarryPass Android — System-wide Deterministic Autofill Service
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import io.github.lingyicute.starrypass.MainActivity
import io.github.lingyicute.starrypass.R
import io.github.lingyicute.starrypass.StarryPassApp
import io.github.lingyicute.starrypass.data.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class StarryPassAutofillService : AutofillService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requestCounter = AtomicInteger((SystemClock.uptimeMillis() and 0x3FFFFFFF).toInt())

    companion object {
        // Ensures the locked dataset remains visible even if the user already typed characters in the input box.
        private val MATCH_ALL_PATTERN: Pattern = Pattern.compile(".*", Pattern.DOTALL)
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val latestStructure = request.fillContexts.lastOrNull()?.structure
        if (latestStructure == null) {
            callback.onSuccess(null)
            return
        }

        val isManualRequest = (request.flags and FillRequest.FLAG_MANUAL_REQUEST) != 0
        val parsed = AssistStructureParser.parse(latestStructure)
        val targetIds = parsed.resolveTargetIds(isManualRequest)

        // Never offer autofill inside StarryPass itself or when no targetable input field exists
        if (parsed.packageName == packageName || targetIds.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        val job = serviceScope.launch {
            val app = application as StarryPassApp
            val matchedPreset = app.preferencesRepository.findMatchingPreset(
                webDomain = parsed.webDomain,
                packageName = parsed.packageName
            )
            val suggestedCode = matchedPreset?.code
                ?: PreferencesRepository.suggestCodeFromContext(parsed.webDomain, parsed.packageName)

            val title = getString(R.string.autofill_prompt_title)
            val subtitle = if (suggestedCode.isNotEmpty()) {
                getString(R.string.autofill_prompt_subtitle_code, suggestedCode)
            } else {
                getString(R.string.autofill_prompt_subtitle_generic)
            }

            val authIntent = Intent(this@StarryPassAutofillService, AutofillAuthActivity::class.java).apply {
                putParcelableArrayListExtra(
                    AutofillAuthActivity.EXTRA_PASSWORD_IDS,
                    ArrayList(targetIds)
                )
                putExtra(AutofillAuthActivity.EXTRA_MANUAL_REQUEST, isManualRequest)
                putExtra(AutofillAuthActivity.EXTRA_SUGGESTED_CODE, suggestedCode)
                putExtra(
                    AutofillAuthActivity.EXTRA_TARGET_LABEL,
                    parsed.webDomain ?: parsed.packageName ?: ""
                )
                if (matchedPreset != null) {
                    putExtra(AutofillAuthActivity.EXTRA_PRESET_ID, matchedPreset.id)
                    putExtra(AutofillAuthActivity.EXTRA_PRESET_MODE, matchedPreset.modeKey)
                    putExtra(AutofillAuthActivity.EXTRA_PRESET_LENGTH, matchedPreset.length)
                    putExtra(AutofillAuthActivity.EXTRA_PRESET_COUNTER, matchedPreset.counter)
                    putExtra(AutofillAuthActivity.EXTRA_PRESET_TIER, matchedPreset.tierKey)
                }
            }

            // FLAG_MUTABLE (explicit component Intent) is required so Android AutofillManager can attach
            // EXTRA_ASSIST_STRUCTURE; FLAG_UPDATE_CURRENT avoids cancelling active inline suggestions.
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(
                this@StarryPassAutofillService,
                requestCounter.incrementAndGet(),
                authIntent,
                pendingFlags
            )

            val dropdownView = RemoteViews(packageName, R.layout.autofill_suggestion_item).apply {
                setTextViewText(R.id.autofill_title, title)
                setTextViewText(R.id.autofill_subtitle, subtitle)
            }

            val inlinePresentation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                request.inlineSuggestionsRequest
                    ?.inlinePresentationSpecs
                    ?.firstOrNull()
                    ?.let { spec -> createInlinePresentation(spec, title, subtitle) }
            } else {
                null
            }

            // Dataset-level authentication: when AutofillAuthActivity returns a populated Dataset in
            // EXTRA_AUTHENTICATION_RESULT, Android immediately replaces this locked Dataset and fills the fields!
            val lockedDataset = buildAuthenticatedDataset(
                targetIds = targetIds,
                pendingIntent = pendingIntent,
                dropdownView = dropdownView,
                inlinePresentation = inlinePresentation
            )

            val fillResponse = FillResponse.Builder()
                .addDataset(lockedDataset)
                .build()

            callback.onSuccess(fillResponse)
        }

        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private fun buildAuthenticatedDataset(
        targetIds: List<AutofillId>,
        pendingIntent: PendingIntent,
        dropdownView: RemoteViews,
        inlinePresentation: InlinePresentation?
    ): Dataset {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val presentationsBuilder = Presentations.Builder()
                .setMenuPresentation(dropdownView)
            if (inlinePresentation != null) {
                presentationsBuilder.setInlinePresentation(inlinePresentation)
            }
            val presentations = presentationsBuilder.build()
            val lockedField = Field.Builder()
                .setFilter(MATCH_ALL_PATTERN)
                .setPresentations(presentations)
                .build()

            return Dataset.Builder(presentations).apply {
                setAuthentication(pendingIntent.intentSender)
                for (id in targetIds) {
                    setField(id, lockedField)
                }
            }.build()
        }

        @Suppress("DEPRECATION")
        return Dataset.Builder(dropdownView).apply {
            setAuthentication(pendingIntent.intentSender)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inlinePresentation != null) {
                setInlinePresentation(inlinePresentation)
                for (id in targetIds) {
                    setValue(id, null, MATCH_ALL_PATTERN, dropdownView, inlinePresentation)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (id in targetIds) {
                    setValue(id, null, MATCH_ALL_PATTERN, dropdownView)
                }
            } else {
                // API 26–27 have no custom-filter overload; use the original API 26 method.
                for (id in targetIds) {
                    setValue(id, null, dropdownView)
                }
            }
        }.build()
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // StarryPass is deterministic & zero-storage for passwords — never intercepts or saves credentials.
        callback.onSuccess()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun createInlinePresentation(
        spec: InlinePresentationSpec,
        title: String,
        subtitle: String
    ): InlinePresentation? {
        val versions = UiVersions.getVersions(spec.style)
        if (!versions.contains(UiVersions.INLINE_UI_VERSION_1)) return null

        val attribution = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val slice = InlineSuggestionUi.newContentBuilder(attribution)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setStartIcon(
                Icon.createWithResource(this, R.drawable.ic_starrypass_badge).apply {
                    setTintBlendMode(android.graphics.BlendMode.DST)
                }
            )
            .build()
            .slice

        return InlinePresentation(slice, spec, false)
    }
}
