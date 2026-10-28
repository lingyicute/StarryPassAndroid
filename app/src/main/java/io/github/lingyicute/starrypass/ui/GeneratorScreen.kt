/*
 * StarryPass Android — Material You Password Generator Screen
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lingyicute.starrypass.R
import io.github.lingyicute.starrypass.crypto.Spv2ErrorCode
import io.github.lingyicute.starrypass.crypto.Spv2Mode
import io.github.lingyicute.starrypass.crypto.Spv2Spec
import io.github.lingyicute.starrypass.crypto.Spv2Tier
import io.github.lingyicute.starrypass.data.SitePreset
import io.github.lingyicute.starrypass.ui.theme.PasswordMonoTextStyle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GeneratorScreen(
    uiState: GeneratorUiState,
    presets: List<SitePreset>,
    biometricEnrolled: Boolean,
    colorizePassword: Boolean,
    clipboardCountdown: Int,
    onMasterChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onClearCode: () -> Unit,
    onResetAll: () -> Unit,
    onBiometricUnlockClick: () -> Unit,
    onPolicyExpandedChange: (Boolean) -> Unit,
    onLengthChange: (Int, Boolean) -> Unit,
    onModeChange: (Spv2Mode) -> Unit,
    onCounterChange: (Int) -> Unit,
    onTierChange: (Spv2Tier) -> Unit,
    onToggleMaskOutput: () -> Unit,
    onApplyPreset: (SitePreset) -> Unit,
    onSaveCurrentAsPreset: () -> Unit,
    onCopyClick: () -> Unit,
    onClearClipboardNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current

    var showMaster by rememberSaveable { mutableStateOf(false) }
    var justCopied by remember { mutableStateOf(false) }
    var counterText by remember(uiState.counter) { mutableStateOf(uiState.counter.toString()) }

    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1_800L)
            justCopied = false
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 540.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Sensitive clipboard live countdown banner
            AnimatedVisibility(
                visible = clipboardCountdown > 0,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colorScheme.secondaryContainer,
                    contentColor = colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.clipboard_countdown_banner, clipboardCountdown),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        TextButton(
                            onClick = onClearClipboardNow,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Text(stringResource(R.string.clipboard_clear_now))
                        }
                    }
                }
            }

            // Main Generator Card (M3 Surface Container Low + Extra Large 28dp corners)
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Card Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.gen_card_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.gen_card_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onResetAll()
                            },
                            enabled = uiState.master.isNotEmpty() || uiState.code.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.RestartAlt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_reset))
                        }
                    }

                    // Master Password Field
                    OutlinedTextField(
                        value = uiState.master,
                        onValueChange = onMasterChange,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        label = { Text(stringResource(R.string.field_master_label)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (biometricEnrolled) {
                                    IconButton(onClick = onBiometricUnlockClick) {
                                        Icon(
                                            imageVector = Icons.Outlined.Fingerprint,
                                            contentDescription = stringResource(R.string.field_biometric_unlock),
                                            tint = colorScheme.primary
                                        )
                                    }
                                }
                                IconButton(onClick = { showMaster = !showMaster }) {
                                    Icon(
                                        imageVector = if (showMaster) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = stringResource(
                                            if (showMaster) R.string.field_master_hide else R.string.field_master_show
                                        )
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
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        )
                    )

                    // Site Code Field
                    OutlinedTextField(
                        value = uiState.code,
                        onValueChange = onCodeChange,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        label = { Text(stringResource(R.string.field_code_label)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Tag,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            if (uiState.code.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = onSaveCurrentAsPreset) {
                                        Icon(
                                            imageVector = Icons.Outlined.BookmarkAdd,
                                            contentDescription = stringResource(R.string.action_save_as_preset),
                                            tint = colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = onClearCode) {
                                        Icon(
                                            imageVector = Icons.Outlined.Clear,
                                            contentDescription = stringResource(R.string.field_code_clear)
                                        )
                                    }
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        )
                    )

                    // Quick Preset Chips (when user has saved site presets)
                    if (presets.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val activeTrimmed = Spv2Spec.ecmaTrim(uiState.code)
                            presets.take(12).forEach { preset ->
                                val selected = activeTrimmed == preset.code &&
                                    uiState.counter == preset.counter &&
                                    uiState.mode == preset.mode &&
                                    uiState.length == preset.length
                                FilterChip(
                                    selected = selected,
                                    onClick = { onApplyPreset(preset) },
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

                    // Expandable Site Policy Accordion (M3 Surface Container High)
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPolicyExpandedChange(!uiState.policyExpanded) }
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.policy_summary),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.policy_summary_short,
                                            uiState.length,
                                            modeLabelText(uiState.mode),
                                            uiState.counter,
                                            tierShortLabelText(uiState.tier)
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorScheme.primary
                                    )
                                }
                                Icon(
                                    imageVector = if (uiState.policyExpanded) {
                                        Icons.Outlined.ExpandLess
                                    } else {
                                        Icons.Outlined.ExpandMore
                                    },
                                    contentDescription = null,
                                    tint = colorScheme.onSurfaceVariant
                                )
                            }

                            AnimatedVisibility(
                                visible = uiState.policyExpanded,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))

                                    // 1. Password Length: Quick Chips (16/20/24 or 4/6/8 for PIN) + 4..64 Custom Slider
                                    Column {
                                        Text(
                                            text = stringResource(R.string.policy_length_label, uiState.length),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        val quickLengths = if (uiState.mode == Spv2Mode.PIN) {
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
                                                    selected = uiState.length == presetLen,
                                                    onClick = { onLengthChange(presetLen, true) },
                                                    label = { Text(presetLen.toString()) }
                                                )
                                            }
                                        }
                                        Slider(
                                            value = uiState.length.toFloat(),
                                            onValueChange = { v ->
                                                onLengthChange(v.roundToInt(), false)
                                            },
                                            valueRange = Spv2Spec.MIN_LENGTH.toFloat()..Spv2Spec.MAX_LENGTH.toFloat(),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    // 2. Character Set Mode
                                    Column {
                                        Text(
                                            text = stringResource(R.string.policy_mode_label),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                            Spv2Mode.entries.forEachIndexed { idx, modeOption ->
                                                SegmentedButton(
                                                    selected = uiState.mode == modeOption,
                                                    onClick = { onModeChange(modeOption) },
                                                    shape = SegmentedButtonDefaults.itemShape(
                                                        index = idx,
                                                        count = Spv2Mode.entries.size
                                                    )
                                                ) {
                                                    Text(
                                                        text = modeLabelText(modeOption),
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
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
                                                onClick = { onCounterChange(uiState.counter - 1) },
                                                enabled = uiState.counter > Spv2Spec.MIN_COUNTER
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
                                                            onCounterChange(parsed)
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
                                                modifier = Modifier.width(96.dp)
                                            )
                                            FilledTonalIconButton(
                                                onClick = { onCounterChange(uiState.counter + 1) },
                                                enabled = uiState.counter < Spv2Spec.MAX_COUNTER
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Add,
                                                    contentDescription = stringResource(R.string.counter_inc)
                                                )
                                            }
                                        }
                                    }

                                    // 4. Argon2id Hardening Tier
                                    Column {
                                        Text(
                                            text = stringResource(R.string.policy_tier_label),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                            SegmentedButton(
                                                selected = uiState.tier == Spv2Tier.STANDARD,
                                                onClick = { onTierChange(Spv2Tier.STANDARD) },
                                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                                            ) {
                                                Text(stringResource(R.string.tier_standard), maxLines = 1)
                                            }
                                            SegmentedButton(
                                                selected = uiState.tier == Spv2Tier.PARANOID,
                                                onClick = { onTierChange(Spv2Tier.PARANOID) },
                                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                                            ) {
                                                Text(stringResource(R.string.tier_paranoid), maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Derived Password Output Surface
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.result_caption),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colorScheme.onSurfaceVariant
                                    )
                                    if (uiState.derivedPassword != null && !uiState.isDeriving) {
                                        Surface(
                                            shape = CircleShape,
                                            color = colorScheme.primaryContainer.copy(alpha = 0.7f),
                                            contentColor = colorScheme.onPrimaryContainer
                                        ) {
                                            Text(
                                                text = stringResource(
                                                    if (uiState.lastCached) R.string.result_cached_badge else R.string.result_fresh_badge,
                                                    uiState.lastElapsedMs
                                                ),
                                                style = MaterialTheme.typography.labelMedium,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.result_length_badge, uiState.length),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colorScheme.onSurfaceVariant
                                    )
                                    if (uiState.derivedPassword != null) {
                                        IconButton(
                                            onClick = onToggleMaskOutput,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (uiState.maskOutput) {
                                                    Icons.Outlined.Visibility
                                                } else {
                                                    Icons.Outlined.VisibilityOff
                                                },
                                                contentDescription = stringResource(R.string.result_mask_output),
                                                modifier = Modifier.size(18.dp),
                                                tint = colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            // Password text or placeholder
                            val pwd = uiState.derivedPassword
                            if (pwd != null) {
                                SelectionContainer {
                                    Text(
                                        text = buildColorizedPassword(
                                            password = pwd,
                                            colorize = colorizePassword,
                                            masked = uiState.maskOutput,
                                            colorScheme = colorScheme
                                        ),
                                        style = PasswordMonoTextStyle,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            } else {
                                val placeholderText = when {
                                    uiState.errorCode == Spv2ErrorCode.OUT_OF_MEMORY ->
                                        stringResource(R.string.error_derive_oom)
                                    uiState.errorCode != null ->
                                        stringResource(R.string.error_derive_generic)
                                    uiState.isDeriving ->
                                        stringResource(R.string.placeholder_deriving)
                                    uiState.master.isNotEmpty() || uiState.code.isNotEmpty() ->
                                        stringResource(R.string.placeholder_complete_both)
                                    else ->
                                        stringResource(R.string.placeholder_enter_both)
                                }
                                Text(
                                    text = placeholderText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (uiState.errorCode != null) {
                                        colorScheme.error
                                    } else {
                                        colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }

                            if (uiState.isDeriving) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .clip(CircleShape)
                                )
                            }

                            Spacer(Modifier.height(14.dp))
                            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.6f))
                            Spacer(Modifier.height(12.dp))

                            // Strength Meter + Copy Button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                StrengthMeterBar(
                                    level = uiState.strength.level,
                                    label = strengthLabelText(uiState.strength),
                                    modifier = Modifier.weight(1f)
                                )

                                Spacer(Modifier.width(12.dp))

                                val copyContainer by animateColorAsState(
                                    targetValue = if (justCopied) colorScheme.primaryContainer else colorScheme.primary,
                                    label = "copyBg"
                                )
                                val copyContent by animateColorAsState(
                                    targetValue = if (justCopied) colorScheme.onPrimaryContainer else colorScheme.onPrimary,
                                    label = "copyFg"
                                )

                                Button(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        justCopied = true
                                        onCopyClick()
                                    },
                                    enabled = pwd != null,
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = copyContainer,
                                        contentColor = copyContent
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (justCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(
                                            if (justCopied) R.string.action_copied else R.string.action_copy
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Offline Zero-Storage Security Notice
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .padding(top = 2.dp)
                            )
                            Text(
                                text = stringResource(R.string.security_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // How It Works Card
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.how_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colorScheme.onSurface
                    )
                    HowStepRow(
                        number = "1",
                        title = stringResource(R.string.how_step1_title),
                        desc = stringResource(R.string.how_step1_desc)
                    )
                    HowStepRow(
                        number = "2",
                        title = stringResource(R.string.how_step2_title),
                        desc = stringResource(R.string.how_step2_desc)
                    )
                    HowStepRow(
                        number = "3",
                        title = stringResource(R.string.how_step3_title),
                        desc = stringResource(R.string.how_step3_desc)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StrengthMeterBar(
    level: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val activeColor = when (level) {
        1 -> colorScheme.error
        2 -> colorScheme.tertiary
        3, 4 -> colorScheme.primary
        else -> colorScheme.onSurfaceVariant
    }
    val filledBars = when (level) {
        1 -> 1
        2 -> 2
        3 -> 3
        4 -> 4
        else -> 0
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 1..4) {
                val barColor by animateColorAsState(
                    targetValue = if (i <= filledBars) activeColor else colorScheme.surfaceContainerHighest,
                    label = "strengthBar$i"
                )
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(barColor)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = activeColor,
            fontWeight = if (level == 4) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun HowStepRow(
    number: String,
    title: String,
    desc: String
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onPrimaryContainer
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}
