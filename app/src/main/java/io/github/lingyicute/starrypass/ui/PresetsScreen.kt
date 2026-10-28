/*
 * StarryPass Android — Non-sensitive Site Policy Presets Screen
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lingyicute.starrypass.R
import io.github.lingyicute.starrypass.crypto.Spv2Mode
import io.github.lingyicute.starrypass.crypto.Spv2Spec
import io.github.lingyicute.starrypass.crypto.Spv2Tier
import io.github.lingyicute.starrypass.data.SitePreset
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetsScreen(
    presets: List<SitePreset>,
    onUsePreset: (SitePreset) -> Unit,
    onUpsertPreset: (SitePreset) -> Unit,
    onRotatePreset: (SitePreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var editingPreset by remember { mutableStateOf<SitePreset?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }

    val filtered = remember(presets, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            presets
        } else {
            presets.filter {
                it.code.lowercase().contains(q) ||
                    it.alias.lowercase().contains(q) ||
                    it.autofillDomain.lowercase().contains(q) ||
                    it.autofillPackage.lowercase().contains(q)
            }
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
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header card
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.presets_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.presets_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(
                    onClick = {
                        editingPreset = null
                        showEditDialog = true
                    }
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.presets_add))
                }
            }

            if (presets.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text(stringResource(R.string.presets_search_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (presets.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Bookmarks,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = stringResource(R.string.presets_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.presets_empty_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filtered, key = { it.id }) { preset ->
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = preset.displayName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colorScheme.onSurface
                                        )
                                        if (preset.alias.isNotBlank()) {
                                            Text(
                                                text = stringResource(R.string.autofill_prompt_subtitle_code, preset.code),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colorScheme.primary
                                            )
                                        }
                                    }
                                    Row {
                                        IconButton(
                                            onClick = {
                                                editingPreset = preset
                                                showEditDialog = true
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Edit,
                                                contentDescription = stringResource(R.string.preset_dialog_edit_title)
                                            )
                                        }
                                        IconButton(onClick = { onDeletePreset(preset.id) }) {
                                            Icon(
                                                imageVector = Icons.Outlined.DeleteOutline,
                                                contentDescription = stringResource(R.string.action_delete),
                                                tint = colorScheme.error
                                            )
                                        }
                                    }
                                }

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    AssistChip(
                                        onClick = { onUsePreset(preset) },
                                        label = {
                                            Text(
                                                stringResource(
                                                    R.string.policy_summary_short,
                                                    preset.length,
                                                    modeLabelText(preset.mode),
                                                    preset.counter,
                                                    tierShortLabelText(preset.tier)
                                                )
                                            )
                                        }
                                    )
                                    if (preset.autofillDomain.isNotEmpty()) {
                                        AssistChip(
                                            onClick = { onUsePreset(preset) },
                                            label = { Text(preset.autofillDomain) }
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                                ) {
                                    OutlinedButton(onClick = { onRotatePreset(preset) }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Autorenew,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.preset_rotate_plus_one))
                                    }
                                    Button(onClick = { onUsePreset(preset) }) {
                                        Icon(
                                            imageVector = Icons.Outlined.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.action_apply))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        PresetEditDialog(
            initial = editingPreset,
            onDismiss = { showEditDialog = false },
            onConfirm = { updated ->
                onUpsertPreset(updated)
                showEditDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetEditDialog(
    initial: SitePreset?,
    onDismiss: () -> Unit,
    onConfirm: (SitePreset) -> Unit
) {
    var code by remember { mutableStateOf(initial?.code ?: "") }
    var alias by remember { mutableStateOf(initial?.alias ?: "") }
    var length by remember { mutableIntStateOf(initial?.length ?: Spv2Spec.DEFAULT_LENGTH) }
    var mode by remember { mutableStateOf(initial?.mode ?: Spv2Mode.FULL) }
    var counterText by remember { mutableStateOf((initial?.counter ?: 1).toString()) }
    var tier by remember { mutableStateOf(initial?.tier ?: Spv2Tier.STANDARD) }
    var domain by remember { mutableStateOf(initial?.autofillDomain ?: "") }
    var pkg by remember { mutableStateOf(initial?.autofillPackage ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.preset_dialog_add_title else R.string.preset_dialog_edit_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.field_code_label)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.preset_field_alias)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.policy_length_label, length),
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = length.toFloat(),
                    onValueChange = { length = it.roundToInt() },
                    valueRange = Spv2Spec.MIN_LENGTH.toFloat()..Spv2Spec.MAX_LENGTH.toFloat()
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    Spv2Mode.entries.forEachIndexed { idx, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(idx, Spv2Mode.entries.size)
                        ) {
                            Text(modeLabelText(m), maxLines = 1)
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = counterText,
                        onValueChange = { counterText = it.filter(Char::isDigit).take(4) },
                        singleLine = true,
                        label = { Text("#") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp)
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                        SegmentedButton(
                            selected = tier == Spv2Tier.STANDARD,
                            onClick = { tier = Spv2Tier.STANDARD },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) {
                            Text("64 MiB", maxLines = 1)
                        }
                        SegmentedButton(
                            selected = tier == Spv2Tier.PARANOID,
                            onClick = { tier = Spv2Tier.PARANOID },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) {
                            Text("256 MiB", maxLines = 1)
                        }
                    }
                }
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.preset_field_domain)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pkg,
                    onValueChange = { pkg = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.preset_field_package)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = Spv2Spec.ecmaTrim(code).isNotEmpty(),
                onClick = {
                    val ctr = Spv2Spec.clampCounter(counterText.toIntOrNull())
                    val result = (initial ?: SitePreset(code = code)).copy(
                        code = Spv2Spec.ecmaTrim(code),
                        alias = alias.trim(),
                        length = Spv2Spec.clampLength(length),
                        modeKey = mode.key,
                        counter = ctr,
                        tierKey = tier.key,
                        autofillDomain = domain.trim(),
                        autofillPackage = pkg.trim()
                    )
                    onConfirm(result)
                }
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
