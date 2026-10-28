/*
 * StarryPass Android — Root Material You Scaffold with Ambient Tonal Blobs
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.lingyicute.starrypass.R
import io.github.lingyicute.starrypass.StarryPassApp
import io.github.lingyicute.starrypass.data.PresetRotationResult
import io.github.lingyicute.starrypass.data.ThemePreference
import io.github.lingyicute.starrypass.security.ScreenSecurityEffect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarryPassRootScreen(
    activity: FragmentActivity,
    viewModel: MainViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings by viewModel.settingsState.collectAsState()
    val presets by viewModel.presetsState.collectAsState()
    val biometricEnrolled by viewModel.biometricEnrolledState.collectAsState()
    val clipboardCountdown by viewModel.clipboardCountdownState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    ScreenSecurityEffect(flagSecureEnabled = settings.flagSecure)

    val colorScheme = MaterialTheme.colorScheme
    val blobPrimary = colorScheme.primaryContainer.copy(alpha = 0.42f)
    val blobTertiary = colorScheme.tertiaryContainer.copy(alpha = 0.30f)

    // Pre-fetch snackbar strings cleanly inside composition
    val copiedMsg = stringResource(R.string.clipboard_copied_snackbar)
    val copyFailedMsg = stringResource(R.string.clipboard_copy_failed)
    val presetSavedMsg = stringResource(R.string.preset_saved_toast)
    val clipboardClearedMsg = stringResource(R.string.clipboard_cleared_toast)
    val bioEnrolledMsg = stringResource(R.string.biometric_enrolled_toast)
    val bioRevokedMsg = stringResource(R.string.biometric_revoked_toast)
    val appName = stringResource(R.string.app_name)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .drawBehind {
                val r1 = size.maxDimension * 0.45f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(blobPrimary, Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.06f),
                        radius = r1
                    ),
                    radius = r1,
                    center = Offset(size.width * 0.92f, size.height * 0.06f)
                )
                val r2 = size.maxDimension * 0.38f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(blobTertiary, Color.Transparent),
                        center = Offset(size.width * 0.08f, size.height * 0.92f),
                        radius = r2
                    ),
                    radius = r2,
                    center = Offset(size.width * 0.08f, size.height * 0.92f)
                )
            }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_starrypass_glyph),
                                    contentDescription = null,
                                    tint = colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.app_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.app_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        FilledTonalIconButton(
                            onClick = viewModel::cycleTheme,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            AnimatedContent(
                                targetState = settings.theme,
                                transitionSpec = {
                                    (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.75f))
                                        .togetherWith(fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.75f))
                                },
                                label = "themeIconTransition"
                            ) { currentTheme ->
                                Icon(
                                    imageVector = when (currentTheme) {
                                        ThemePreference.SYSTEM -> Icons.Outlined.BrightnessAuto
                                        ThemePreference.LIGHT -> Icons.Outlined.LightMode
                                        ThemePreference.DARK -> Icons.Outlined.DarkMode
                                    },
                                    contentDescription = stringResource(R.string.settings_theme_title)
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = colorScheme.surfaceContainer.copy(alpha = 0.94f)
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == 0) Icons.Filled.Key else Icons.Outlined.Key,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.nav_generator)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == 1) Icons.Filled.Bookmarks else Icons.Outlined.Bookmarks,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.nav_presets)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == 2) Icons.Filled.Shield else Icons.Outlined.Shield,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.nav_security)) }
                    )
                }
            }
        ) { innerPadding ->
            when (selectedTab) {
                0 -> GeneratorScreen(
                    uiState = uiState,
                    presets = presets,
                    biometricEnrolled = biometricEnrolled,
                    colorizePassword = settings.colorizePassword,
                    clipboardCountdown = clipboardCountdown,
                    onMasterChange = viewModel::onMasterChanged,
                    onCodeChange = viewModel::onCodeChanged,
                    onClearCode = viewModel::clearCode,
                    onResetAll = viewModel::resetAll,
                    onBiometricUnlockClick = {
                        viewModel.biometricVault.unlockMasterPassword(
                            activity = activity,
                            onSuccess = { unlocked -> viewModel.onMasterChanged(unlocked) },
                            onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } }
                        )
                    },
                    onPolicyExpandedChange = viewModel::setPolicyExpanded,
                    onLengthChange = viewModel::onLengthChanged,
                    onModeChange = viewModel::onModeChanged,
                    onCounterChange = viewModel::onCounterChanged,
                    onTierChange = viewModel::onTierChanged,
                    onToggleMaskOutput = viewModel::toggleMaskOutput,
                    onApplyPreset = viewModel::applyPreset,
                    onSaveCurrentAsPreset = {
                        viewModel.saveCurrentAsPreset {
                            scope.launch { snackbarHostState.showSnackbar(presetSavedMsg) }
                        }
                    },
                    onCopyClick = {
                        val ok = viewModel.copyCurrentPassword(appName)
                        scope.launch {
                            snackbarHostState.showSnackbar(if (ok) copiedMsg else copyFailedMsg)
                        }
                    },
                    onClearClipboardNow = {
                        viewModel.clearClipboardNow()
                        scope.launch { snackbarHostState.showSnackbar(clipboardClearedMsg) }
                    },
                    modifier = Modifier.padding(innerPadding)
                )

                1 -> PresetsScreen(
                    presets = presets,
                    onUsePreset = { preset ->
                        viewModel.applyPreset(preset)
                        selectedTab = 0
                    },
                    onUpsertPreset = viewModel::upsertPreset,
                    onRotatePreset = { preset ->
                        viewModel.incrementPresetRotation(preset.id) { result ->
                            val message = when (result) {
                                is PresetRotationResult.Updated -> activity.getString(
                                    R.string.preset_rotated_toast, result.preset.displayName, result.preset.counter
                                )
                                PresetRotationResult.LimitReached -> activity.getString(R.string.preset_rotation_limit_message)
                                PresetRotationResult.NotFound -> activity.getString(R.string.preset_rotation_missing)
                            }
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        }
                    },
                    onDeletePreset = viewModel::deletePreset,
                    modifier = Modifier.padding(innerPadding)
                )

                2 -> SecuritySettingsScreen(
                    settings = settings,
                    katVerified = uiState.katVerified,
                    biometricEnrolled = biometricEnrolled,
                    biometricHardwareStatus = viewModel.queryBiometricHardware(),
                    currentMasterDraft = uiState.master,
                    sessionWipes = (activity.application as StarryPassApp).sessionWipes,
                    onDynamicColorChange = viewModel::setDynamicColor,
                    onThemeChange = viewModel::setTheme,
                    onColorizeChange = viewModel::setColorizePassword,
                    onFlagSecureChange = viewModel::setFlagSecure,
                    onWipeOnBgChange = viewModel::setWipeOnBackground,
                    onClipboardClearSecondsChange = viewModel::setClipboardAutoClearSeconds,
                    onEnrollBiometric = { secret ->
                        viewModel.biometricVault.enrollMasterPassword(
                            activity = activity,
                            masterPassword = secret,
                            onSuccess = {
                                scope.launch { snackbarHostState.showSnackbar(bioEnrolledMsg) }
                            },
                            onError = { err ->
                                scope.launch { snackbarHostState.showSnackbar(err) }
                            }
                        )
                    },
                    onRevokeBiometric = {
                        viewModel.biometricVault.clearVault()
                        scope.launch { snackbarHostState.showSnackbar(bioRevokedMsg) }
                    },
                    onRefreshBiometric = { viewModel.biometricVault.refreshState() },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}
