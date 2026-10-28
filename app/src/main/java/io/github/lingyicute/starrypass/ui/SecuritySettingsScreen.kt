/*
 * StarryPass Android — Security, Biometric Vault, Autofill & Material You Settings Screen
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.lingyicute.starrypass.R
import io.github.lingyicute.starrypass.data.AppSettings
import io.github.lingyicute.starrypass.data.ThemePreference
import io.github.lingyicute.starrypass.security.BiometricHardwareStatus
import io.github.lingyicute.starrypass.security.SessionWipeSignal

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SecuritySettingsScreen(
    settings: AppSettings,
    katVerified: Boolean,
    biometricEnrolled: Boolean,
    biometricHardwareStatus: BiometricHardwareStatus,
    currentMasterDraft: String,
    sessionWipes: SessionWipeSignal,
    onDynamicColorChange: (Boolean) -> Unit,
    onThemeChange: (ThemePreference) -> Unit,
    onColorizeChange: (Boolean) -> Unit,
    onFlagSecureChange: (Boolean) -> Unit,
    onWipeOnBgChange: (Boolean) -> Unit,
    onClipboardClearSecondsChange: (Int) -> Unit,
    onEnrollBiometric: (String) -> Unit,
    onRevokeBiometric: () -> Unit,
    onRefreshBiometric: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colorScheme = MaterialTheme.colorScheme

    var autofillEnabled by remember { mutableStateOf(false) }
    var showEnrollDialog by remember { mutableStateOf(false) }
    var observedWipe by remember(sessionWipes) { mutableStateOf(sessionWipes.generation.value) }

    LaunchedEffect(sessionWipes) {
        sessionWipes.generation.collect { generation ->
            if (generation != observedWipe) {
                observedWipe = generation
                showEnrollDialog = false
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val afm = context.getSystemService(AutofillManager::class.java)
                autofillEnabled = afm?.hasEnabledAutofillServices() == true
                onRefreshBiometric()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            // 1. Material You & Appearance
            SettingsSectionCard(
                icon = Icons.Outlined.Palette,
                title = stringResource(R.string.settings_appearance_section)
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_dynamic_color_title),
                        desc = stringResource(R.string.settings_dynamic_color_desc),
                        checked = settings.dynamicColor,
                        onCheckedChange = onDynamicColorChange
                    )
                    HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.45f))
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_theme_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface
                    )
                    val themes = ThemePreference.entries
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        themes.forEachIndexed { index, pref ->
                            SegmentedButton(
                                selected = settings.theme == pref,
                                onClick = { onThemeChange(pref) },
                                shape = SegmentedButtonDefaults.itemShape(index, themes.size)
                            ) {
                                Text(
                                    text = when (pref) {
                                        ThemePreference.SYSTEM -> stringResource(R.string.theme_system)
                                        ThemePreference.LIGHT -> stringResource(R.string.theme_light)
                                        ThemePreference.DARK -> stringResource(R.string.theme_dark)
                                    },
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.45f))

                SettingsToggleRow(
                    title = stringResource(R.string.settings_colorize_title),
                    desc = stringResource(R.string.settings_colorize_desc),
                    checked = settings.colorizePassword,
                    onCheckedChange = onColorizeChange
                )
            }

            // 2. Hardware Biometric Vault (Opt-In)
            SettingsSectionCard(
                icon = Icons.Outlined.Fingerprint,
                title = stringResource(R.string.settings_biometric_section)
            ) {
                Text(
                    text = stringResource(
                        if (biometricEnrolled) {
                            R.string.settings_biometric_enrolled_title
                        } else {
                            R.string.settings_biometric_not_enrolled_title
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (biometricEnrolled) colorScheme.primary else colorScheme.onSurface
                )
                Text(
                    text = stringResource(
                        if (biometricEnrolled) {
                            R.string.settings_biometric_enrolled_desc
                        } else {
                            R.string.settings_biometric_not_enrolled_desc
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )

                if (biometricEnrolled) {
                    OutlinedButton(
                        onClick = onRevokeBiometric,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.error)
                    ) {
                        Text(stringResource(R.string.settings_biometric_revoke_btn))
                    }
                } else if (biometricHardwareStatus == BiometricHardwareStatus.AVAILABLE) {
                    FilledTonalButton(onClick = { showEnrollDialog = true }) {
                        Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_biometric_enroll_btn))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.settings_biometric_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.error
                    )
                }
            }

            // 3. System Autofill Service
            SettingsSectionCard(
                icon = Icons.Outlined.AutoFixHigh,
                title = stringResource(R.string.settings_autofill_section)
            ) {
                Text(
                    text = stringResource(
                        if (autofillEnabled) R.string.settings_autofill_enabled else R.string.settings_autofill_disabled
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (autofillEnabled) colorScheme.primary else colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_autofill_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                            Uri.parse("package:${context.packageName}")
                        )
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                            }
                    }
                ) {
                    Text(stringResource(R.string.settings_autofill_open_btn))
                }
            }

            // 4. Runtime Hardening
            SettingsSectionCard(
                icon = Icons.Outlined.Security,
                title = stringResource(R.string.settings_hardening_section)
            ) {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_flag_secure_title),
                    desc = stringResource(R.string.settings_flag_secure_desc),
                    checked = settings.flagSecure,
                    onCheckedChange = onFlagSecureChange
                )
                HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.45f))

                SettingsToggleRow(
                    title = stringResource(R.string.settings_wipe_bg_title),
                    desc = stringResource(R.string.settings_wipe_bg_desc),
                    checked = settings.wipeOnBackground,
                    onCheckedChange = onWipeOnBgChange
                )
                HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.45f))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_clipboard_clear_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface
                    )
                    val options = listOf(0, 30, 45, 60, 120)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        options.forEach { sec ->
                            FilterChip(
                                selected = settings.clipboardAutoClearSeconds == sec,
                                onClick = { onClipboardClearSecondsChange(sec) },
                                label = {
                                    Text(
                                        if (sec == 0) {
                                            stringResource(R.string.clipboard_off)
                                        } else {
                                            stringResource(R.string.clipboard_seconds, sec)
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // 5. Cryptographic Audit & Compliance
            SettingsSectionCard(
                icon = Icons.Outlined.Verified,
                title = stringResource(R.string.settings_audit_section)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colorScheme.primaryContainer.copy(alpha = 0.65f),
                    contentColor = colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.AirplanemodeActive, contentDescription = null)
                        Column {
                            Text(
                                text = stringResource(R.string.audit_zero_network_title),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.audit_zero_network_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(
                        if (katVerified) R.string.audit_kat_verified else R.string.audit_kat_pending
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (katVerified) colorScheme.primary else colorScheme.onSurfaceVariant
                )

                Text(
                    text = stringResource(R.string.audit_primitives_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = stringResource(R.string.audit_license_footer),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        }
    }

    if (showEnrollDialog) {
        BiometricEnrollDialog(
            initialMaster = currentMasterDraft,
            onDismiss = { showEnrollDialog = false },
            onConfirm = { secret ->
                showEnrollDialog = false
                onEnrollBiometric(secret)
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BiometricEnrollDialog(
    initialMaster: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var master by remember { mutableStateOf(initialMaster) }
    var visible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.biometric_enroll_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.biometric_enroll_dialog_desc),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = master,
                    onValueChange = { master = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.field_master_label)) },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = master.isNotEmpty(),
                onClick = { onConfirm(master) }
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
