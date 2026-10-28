/*
 * StarryPass Android — Floating Material You Autofill Authentication & Policy Sheet
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.autofill

import android.app.Activity
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.Presentations
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.lingyicute.starrypass.R
import io.github.lingyicute.starrypass.StarryPassApp
import io.github.lingyicute.starrypass.crypto.DeriveRequest
import io.github.lingyicute.starrypass.crypto.Spv2Mode
import io.github.lingyicute.starrypass.crypto.Spv2Spec
import io.github.lingyicute.starrypass.crypto.Spv2Tier
import io.github.lingyicute.starrypass.data.AppSettings
import io.github.lingyicute.starrypass.ui.modeLabelText
import io.github.lingyicute.starrypass.ui.theme.StarryPassTheme
import io.github.lingyicute.starrypass.ui.tierShortLabelText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class AutofillAuthActivity : FragmentActivity() {

    companion object {
        const val EXTRA_PASSWORD_IDS = "extra_password_ids"
        const val EXTRA_MANUAL_REQUEST = "extra_manual_request"
        const val EXTRA_SUGGESTED_CODE = "extra_suggested_code"
        const val EXTRA_TARGET_LABEL = "extra_target_label"
        const val EXTRA_PRESET_ID = "extra_preset_id"
        const val EXTRA_PRESET_MODE = "extra_preset_mode"
        const val EXTRA_PRESET_LENGTH = "extra_preset_length"
        const val EXTRA_PRESET_COUNTER = "extra_preset_counter"
        const val EXTRA_PRESET_TIER = "extra_preset_tier"
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val fallbackIds: List<AutofillId> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_PASSWORD_IDS, AutofillId::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(EXTRA_PASSWORD_IDS)
        }.orEmpty()

        val liveStructure: AssistStructure? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        }

        val isManualRequest = intent.getBooleanExtra(EXTRA_MANUAL_REQUEST, false)
        // Prefer fresh AutofillIds parsed from the live AssistStructure (critical for Chrome/WebView virtual nodes),
        // falling back cleanly to EXTRA_PASSWORD_IDS passed from StarryPassAutofillService.
        val targetIds: List<AutofillId> = liveStructure
            ?.let { AssistStructureParser.parse(it).resolveTargetIds(isManualRequest) }
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackIds

        if (targetIds.isEmpty()) {
            cancelAutofill()
            return
        }

        val initialCode = intent.getStringExtra(EXTRA_SUGGESTED_CODE).orEmpty()
        val targetLabel = intent.getStringExtra(EXTRA_TARGET_LABEL).orEmpty()
        val initialMode = Spv2Mode.fromKey(intent.getStringExtra(EXTRA_PRESET_MODE))
        val initialLength = Spv2Spec.clampLength(
            intent.getIntExtra(EXTRA_PRESET_LENGTH, Spv2Spec.DEFAULT_LENGTH)
        )
        val initialCounter = Spv2Spec.clampCounter(
            intent.getIntExtra(EXTRA_PRESET_COUNTER, Spv2Spec.DEFAULT_COUNTER)
        )
        val initialTier = Spv2Tier.fromKey(intent.getStringExtra(EXTRA_PRESET_TIER))

        val app = application as StarryPassApp

        setContent {
            val settings by app.preferencesRepository.settingsFlow.collectAsState(initial = AppSettings())
            val presets by app.preferencesRepository.presetsFlow.collectAsState(initial = emptyList())
            val bioEnrolled by app.biometricVault.isEnrolled.collectAsState()
            val scope = rememberCoroutineScope()

            // Respect user's screenshot protection setting inside Autofill sheet as well
            if (settings.flagSecure) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }

            BackHandler { cancelAutofill() }

            // Never persist the master password in saved instance state.
            var master by remember { mutableStateOf("") }
            var code by rememberSaveable { mutableStateOf(initialCode) }
            var mode by rememberSaveable { mutableStateOf(initialMode) }
            var length by rememberSaveable { mutableIntStateOf(initialLength) }
            var counter by rememberSaveable { mutableIntStateOf(initialCounter) }
            var counterText by remember(counter) { mutableStateOf(counter.toString()) }
            var tier by rememberSaveable { mutableStateOf(initialTier) }
            var policyExpanded by rememberSaveable { mutableStateOf(false) }
            var showMaster by rememberSaveable { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            var errorMsg by remember { mutableStateOf<String?>(null) }
            var deriveJob by remember { mutableStateOf<Job?>(null) }
            var observedWipe by remember { mutableStateOf(app.sessionWipes.generation.value) }

            LaunchedEffect(app) {
                app.sessionWipes.generation.collect { generation ->
                    if (generation != observedWipe) {
                        observedWipe = generation
                        deriveJob?.cancel()
                        deriveJob = null
                        master = ""
                        showMaster = false
                        busy = false
                        errorMsg = null
                    }
                }
            }

            fun executeDerive(secretMaster: String, copyOnly: Boolean) {
                val cleanCode = Spv2Spec.ecmaTrim(code)
                if (secretMaster.isEmpty() || cleanCode.isEmpty()) return
                val generation = app.sessionWipes.generation.value
                val request = DeriveRequest(secretMaster, cleanCode, tier, counter, length, mode)
                busy = true
                errorMsg = null
                deriveJob?.cancel()
                deriveJob = scope.launch {
                    try {
                        val res = app.engine.derive(request)
                        app.engine.wipeSession()
                        // A wipe may be signalled before the Compose collector gets CPU time.
                        if (!app.sessionWipes.isCurrent(generation)) return@launch
                        if (copyOnly) {
                            app.clipboardManager.copySensitivePassword(
                                label = getString(R.string.app_name),
                                secret = res.password,
                                autoClearSeconds = settings.clipboardAutoClearSeconds
                            )
                            cancelAutofill()
                        } else {
                            completeAutofill(targetIds, cleanCode, res.password)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Throwable) {
                        if (app.sessionWipes.isCurrent(generation)) {
                            busy = false
                            errorMsg = e.localizedMessage ?: getString(R.string.error_derive_generic)
                        }
                    }
                }
            }

            fun triggerAction(copyOnly: Boolean) {
                if (master.isNotEmpty()) {
                    executeDerive(master, copyOnly = copyOnly)
                } else if (bioEnrolled) {
                    app.biometricVault.unlockMasterPassword(
                        activity = this@AutofillAuthActivity,
                        onSuccess = { unlocked -> executeDerive(unlocked, copyOnly = copyOnly) },
                        onError = { err -> errorMsg = err }
                    )
                }
            }

            StarryPassTheme(
                themePreference = settings.theme,
                dynamicColor = settings.dynamicColor
            ) {
                val colorScheme = MaterialTheme.colorScheme
                val codeReady = Spv2Spec.ecmaTrim(code).isNotEmpty()
                val canProceed = !busy && codeReady && (master.isNotEmpty() || bioEnrolled)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.38f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { cancelAutofill() },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        color = colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .statusBarsPadding()
                            .widthIn(max = 540.dp)
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { /* consume clicks inside sheet */ }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Sheet Header
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(
                                            colorScheme.primaryContainer,
                                            RoundedCornerShape(12.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_starrypass_glyph),
                                        contentDescription = null,
                                        tint = colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.autofill_sheet_title),
                                        style = MaterialTheme.typography.titleLarge,
                                        color = colorScheme.onSurface
                                    )
                                    if (targetLabel.isNotBlank()) {
                                        Text(
                                            text = stringResource(R.string.autofill_detected_target, targetLabel),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Saved Site Presets Quick Row (1-tap loads code + length + mode + counter + tier)
                            if (presets.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = stringResource(R.string.autofill_presets_label),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val activeCode = Spv2Spec.ecmaTrim(code)
                                        presets.forEach { preset ->
                                            val selected = activeCode == preset.code &&
                                                length == preset.length &&
                                                mode == preset.mode &&
                                                counter == preset.counter &&
                                                tier == preset.tier
                                            FilterChip(
                                                selected = selected,
                                                onClick = {
                                                    code = preset.code
                                                    length = preset.length
                                                    mode = preset.mode
                                                    counter = preset.counter
                                                    tier = preset.tier
                                                },
                                                label = {
                                                    Text(
                                                        if (preset.counter > 1) {
                                                            "${preset.displayName} #${preset.counter}"
                                                        } else {
                                                            preset.displayName
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Site Code Input
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                label = { Text(stringResource(R.string.field_code_label)) },
                                leadingIcon = { Icon(Icons.Outlined.Tag, contentDescription = null) },
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Master Password Input
                            OutlinedTextField(
                                value = master,
                                onValueChange = { master = it },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                label = { Text(stringResource(R.string.field_master_label)) },
                                leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (bioEnrolled) {
                                            IconButton(
                                                onClick = {
                                                    app.biometricVault.unlockMasterPassword(
                                                        activity = this@AutofillAuthActivity,
                                                        onSuccess = { unlocked -> master = unlocked },
                                                        onError = { err -> errorMsg = err }
                                                    )
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Fingerprint,
                                                    contentDescription = stringResource(R.string.field_biometric_unlock),
                                                    tint = colorScheme.primary
                                                )
                                            }
                                        }
                                        IconButton(onClick = { showMaster = !showMaster }) {
                                            Icon(
                                                imageVector = if (showMaster) {
                                                    Icons.Outlined.VisibilityOff
                                                } else {
                                                    Icons.Outlined.Visibility
                                                },
                                                contentDescription = null
                                            )
                                        }
                                    }
                                },
                                visualTransformation = if (showMaster) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Complete Policy Parameters Selector Card (Charset, Length 4..64, Counter 1..9999, Tier)
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { policyExpanded = !policyExpanded }
                                            .padding(horizontal = 14.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Tune,
                                                contentDescription = null,
                                                tint = colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Column {
                                                Text(
                                                    text = stringResource(R.string.autofill_policy_toggle),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = colorScheme.onSurface
                                                )
                                                Text(
                                                    text = stringResource(
                                                        R.string.policy_summary_short,
                                                        length,
                                                        modeLabelText(mode),
                                                        counter,
                                                        tierShortLabelText(tier)
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colorScheme.primary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = if (policyExpanded) {
                                                Icons.Outlined.ExpandLess
                                            } else {
                                                Icons.Outlined.ExpandMore
                                            },
                                            contentDescription = null,
                                            tint = colorScheme.onSurfaceVariant
                                        )
                                    }

                                    AnimatedVisibility(
                                        visible = policyExpanded,
                                        enter = fadeIn() + expandVertically(),
                                        exit = fadeOut() + shrinkVertically()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))

                                            // 1. Character Set Mode
                                            Column {
                                                Text(
                                                    text = stringResource(R.string.policy_mode_label),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = colorScheme.onSurfaceVariant
                                                )
                                                Spacer(Modifier.height(6.dp))
                                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                                    Spv2Mode.entries.forEachIndexed { idx, m ->
                                                        SegmentedButton(
                                                            selected = mode == m,
                                                            onClick = {
                                                                if (m == Spv2Mode.PIN && length > 8) length = 6
                                                                else if (mode == Spv2Mode.PIN && m != Spv2Mode.PIN && length <= 8) length = 16
                                                                mode = m
                                                            },
                                                            shape = SegmentedButtonDefaults.itemShape(
                                                                idx,
                                                                Spv2Mode.entries.size
                                                            )
                                                        ) {
                                                            Text(modeLabelText(m), maxLines = 1)
                                                        }
                                                    }
                                                }
                                            }

                                            // 2. Password Length (Quick Chips + 4..64 Custom Slider)
                                            Column {
                                                Text(
                                                    text = stringResource(R.string.policy_length_label, length),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = colorScheme.onSurfaceVariant
                                                )
                                                Spacer(Modifier.height(4.dp))
                                                val quickLengths = if (mode == Spv2Mode.PIN) {
                                                    listOf(4, 6, 8, 12)
                                                } else {
                                                    listOf(12, 16, 20, 24, 32)
                                                }
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    quickLengths.forEach { presetLen ->
                                                        FilterChip(
                                                            selected = length == presetLen,
                                                            onClick = { length = presetLen },
                                                            label = { Text(presetLen.toString()) }
                                                        )
                                                    }
                                                }
                                                Slider(
                                                    value = length.toFloat(),
                                                    onValueChange = { length = it.roundToInt() },
                                                    valueRange = Spv2Spec.MIN_LENGTH.toFloat()..Spv2Spec.MAX_LENGTH.toFloat(),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }

                                            // 3. Rotation Counter (1..9999)
                                            Column {
                                                Text(
                                                    text = stringResource(R.string.policy_counter_label),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = colorScheme.onSurfaceVariant
                                                )
                                                Spacer(Modifier.height(6.dp))
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    FilledTonalIconButton(
                                                        onClick = {
                                                            if (counter > Spv2Spec.MIN_COUNTER) counter -= 1
                                                        },
                                                        enabled = counter > Spv2Spec.MIN_COUNTER
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Outlined.Remove,
                                                            contentDescription = stringResource(R.string.counter_dec)
                                                        )
                                                    }
                                                    OutlinedTextField(
                                                        value = counterText,
                                                        onValueChange = { raw ->
                                                            val digits = raw.filter { it.isDigit() }.take(4)
                                                            counterText = digits
                                                            digits.toIntOrNull()?.let { parsed ->
                                                                if (parsed in Spv2Spec.MIN_COUNTER..Spv2Spec.MAX_COUNTER) {
                                                                    counter = parsed
                                                                }
                                                            }
                                                        },
                                                        singleLine = true,
                                                        shape = RoundedCornerShape(12.dp),
                                                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                            textAlign = TextAlign.Center,
                                                            fontWeight = FontWeight.SemiBold
                                                        ),
                                                        keyboardOptions = KeyboardOptions(
                                                            keyboardType = KeyboardType.Number,
                                                            imeAction = ImeAction.Done
                                                        ),
                                                        modifier = Modifier.width(92.dp)
                                                    )
                                                    FilledTonalIconButton(
                                                        onClick = {
                                                            if (counter < Spv2Spec.MAX_COUNTER) counter += 1
                                                        },
                                                        enabled = counter < Spv2Spec.MAX_COUNTER
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Outlined.Add,
                                                            contentDescription = stringResource(R.string.counter_inc)
                                                        )
                                                    }
                                                }
                                            }

                                            // 4. Argon2id Hardening Tier (64 MiB / 256 MiB)
                                            Column {
                                                Text(
                                                    text = stringResource(R.string.policy_tier_label),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = colorScheme.onSurfaceVariant
                                                )
                                                Spacer(Modifier.height(6.dp))
                                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                                    SegmentedButton(
                                                        selected = tier == Spv2Tier.STANDARD,
                                                        onClick = { tier = Spv2Tier.STANDARD },
                                                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                                                    ) {
                                                        Text(stringResource(R.string.tier_standard), maxLines = 1)
                                                    }
                                                    SegmentedButton(
                                                        selected = tier == Spv2Tier.PARANOID,
                                                        onClick = { tier = Spv2Tier.PARANOID },
                                                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                                                    ) {
                                                        Text(stringResource(R.string.tier_paranoid), maxLines = 1)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (busy) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }

                            errorMsg?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.error
                                )
                            }

                            // Action Row: Cancel + Copy Fallback + Derive & Fill (or Unlock & Fill)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { cancelAutofill() }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                                Spacer(Modifier.width(6.dp))
                                OutlinedButton(
                                    enabled = canProceed,
                                    onClick = { triggerAction(copyOnly = true) }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.autofill_copy_only_btn))
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    enabled = canProceed,
                                    onClick = { triggerAction(copyOnly = false) }
                                ) {
                                    if (master.isEmpty() && bioEnrolled) {
                                        Icon(
                                            imageVector = Icons.Outlined.Fingerprint,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.autofill_unlock_and_fill_btn))
                                    } else {
                                        Text(stringResource(R.string.autofill_fill_btn))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun cancelAutofill() {
        // Android 12+ requires non-null extras on setResult(RESULT_CANCELED, intent) for Autofill auth activities.
        val cancelIntent = Intent().putExtras(Bundle.EMPTY)
        setResult(Activity.RESULT_CANCELED, cancelIntent)
        finish()
    }

    private fun completeAutofill(
        targetIds: List<AutofillId>,
        codeLabel: String,
        derivedPassword: String
    ) {
        val presentation = RemoteViews(packageName, R.layout.autofill_suggestion_item).apply {
            setTextViewText(R.id.autofill_title, getString(R.string.app_name))
            setTextViewText(R.id.autofill_subtitle, codeLabel)
        }
        val autofillValue = AutofillValue.forText(derivedPassword)

        val unlockedDataset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val presentations = Presentations.Builder()
                .setMenuPresentation(presentation)
                .build()
            val populatedField = Field.Builder()
                .setValue(autofillValue)
                .setPresentations(presentations)
                .build()
            Dataset.Builder(presentations).apply {
                for (id in targetIds) {
                    setField(id, populatedField)
                }
            }.build()
        } else {
            @Suppress("DEPRECATION")
            Dataset.Builder(presentation).apply {
                for (id in targetIds) {
                    setValue(id, autofillValue, presentation)
                }
            }.build()
        }

        val replyIntent = Intent().apply {
            putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, unlockedDataset)
        }
        setResult(Activity.RESULT_OK, replyIntent)
        finish()
    }
}
