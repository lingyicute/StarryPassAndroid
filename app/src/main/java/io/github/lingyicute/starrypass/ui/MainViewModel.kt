/*
 * StarryPass Android — Main Reactive ViewModel
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lingyicute.starrypass.StarryPassApp
import io.github.lingyicute.starrypass.crypto.CrackResistanceEstimator
import io.github.lingyicute.starrypass.crypto.DeriveRequest
import io.github.lingyicute.starrypass.crypto.Spv2ErrorCode
import io.github.lingyicute.starrypass.crypto.Spv2Exception
import io.github.lingyicute.starrypass.crypto.Spv2Mode
import io.github.lingyicute.starrypass.crypto.Spv2Spec
import io.github.lingyicute.starrypass.crypto.Spv2Tier
import io.github.lingyicute.starrypass.crypto.StrengthEstimate
import io.github.lingyicute.starrypass.crypto.StrengthReason
import io.github.lingyicute.starrypass.data.AppSettings
import io.github.lingyicute.starrypass.data.SitePreset
import io.github.lingyicute.starrypass.data.ThemePreference
import io.github.lingyicute.starrypass.security.BiometricHardwareStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GeneratorUiState(
    val master: String = "",
    val code: String = "",
    val length: Int = Spv2Spec.DEFAULT_LENGTH,
    val mode: Spv2Mode = Spv2Mode.FULL,
    val counter: Int = Spv2Spec.DEFAULT_COUNTER,
    val tier: Spv2Tier = Spv2Tier.STANDARD,
    val policyExpanded: Boolean = false,
    val derivedPassword: String? = null,
    val isDeriving: Boolean = false,
    val lastCached: Boolean = false,
    val lastElapsedMs: Long = 0L,
    val errorCode: Spv2ErrorCode? = null,
    val strength: StrengthEstimate = StrengthEstimate(0, 0, StrengthReason.EMPTY),
    val maskOutput: Boolean = false,
    val katVerified: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StarryPassApp
    private val engine = app.engine
    private val prefsRepo = app.preferencesRepository
    val biometricVault = app.biometricVault
    val clipboardManager = app.clipboardManager

    val settingsState: StateFlow<AppSettings> = prefsRepo.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AppSettings()
    )

    val presetsState: StateFlow<List<SitePreset>> = prefsRepo.presetsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList()
    )

    val biometricEnrolledState: StateFlow<Boolean> = biometricVault.isEnrolled
    val clipboardCountdownState: StateFlow<Int> = clipboardManager.remainingSeconds

    private val _uiState = MutableStateFlow(
        GeneratorUiState(katVerified = engine.isSelfTestVerified)
    )
    val uiState: StateFlow<GeneratorUiState> = _uiState.asStateFlow()

    private var deriveJob: Job? = null
    private var deriveSequence: Long = 0L

    init {
        viewModelScope.launch {
            runCatching { engine.runSelfTest() }
            _uiState.update { it.copy(katVerified = engine.isSelfTestVerified) }
        }

        // Automatically clear in-memory master & output when ProcessLifecycleOwner triggers background wipe
        viewModelScope.launch {
            app.sessionWipeEvents.collect {
                deriveJob?.cancel()
                deriveSequence++
                _uiState.update { state ->
                    state.copy(
                        master = "",
                        derivedPassword = null,
                        isDeriving = false,
                        errorCode = null,
                        strength = StrengthEstimate(0, 0, StrengthReason.EMPTY)
                    )
                }
            }
        }
    }

    fun onMasterChanged(newMaster: String) {
        _uiState.update { it.copy(master = newMaster) }
        scheduleDerive(debounceMs = 280L)
    }

    fun onCodeChanged(newCode: String) {
        _uiState.update { it.copy(code = newCode) }
        scheduleDerive(debounceMs = 280L)
    }

    fun clearCode() {
        _uiState.update { it.copy(code = "") }
        scheduleDerive(debounceMs = 0L)
    }

    fun resetAll() {
        deriveJob?.cancel()
        deriveSequence++
        _uiState.update {
            it.copy(
                master = "",
                code = "",
                derivedPassword = null,
                isDeriving = false,
                errorCode = null,
                strength = StrengthEstimate(0, 0, StrengthReason.EMPTY)
            )
        }
        viewModelScope.launch {
            engine.wipeSession()
        }
    }

    fun setPolicyExpanded(expanded: Boolean) {
        _uiState.update { it.copy(policyExpanded = expanded) }
    }

    fun toggleMaskOutput() {
        _uiState.update { it.copy(maskOutput = !it.maskOutput) }
    }

    fun onLengthChanged(newLength: Int, immediate: Boolean = true) {
        val clamped = Spv2Spec.clampLength(newLength)
        _uiState.update { it.copy(length = clamped) }
        scheduleDerive(debounceMs = if (immediate) 40L else 280L)
    }

    fun onModeChanged(newMode: Spv2Mode) {
        _uiState.update { current ->
            val adjustedLen = when {
                // Mirror upstream UX: selecting PIN while >8 defaults to 6-digit PIN
                newMode == Spv2Mode.PIN && current.length > 8 -> 6
                // Returning from short PIN to Full/Alnum restores sensible 16-char default
                current.mode == Spv2Mode.PIN && newMode != Spv2Mode.PIN && current.length <= 8 -> 16
                else -> current.length
            }
            current.copy(mode = newMode, length = adjustedLen)
        }
        scheduleDerive(debounceMs = 0L)
    }

    fun onCounterChanged(newCounter: Int) {
        val clamped = Spv2Spec.clampCounter(newCounter)
        _uiState.update { it.copy(counter = clamped) }
        scheduleDerive(debounceMs = 0L)
    }

    fun onTierChanged(newTier: Spv2Tier) {
        _uiState.update { it.copy(tier = newTier) }
        scheduleDerive(debounceMs = 60L)
    }

    fun applyPreset(preset: SitePreset) {
        _uiState.update {
            it.copy(
                code = preset.code,
                mode = preset.mode,
                length = Spv2Spec.clampLength(preset.length),
                counter = Spv2Spec.clampCounter(preset.counter),
                tier = preset.tier
            )
        }
        scheduleDerive(debounceMs = 0L)
    }

    fun saveCurrentAsPreset(onSaved: () -> Unit) {
        val s = _uiState.value
        val trimmedCode = Spv2Spec.ecmaTrim(s.code)
        if (trimmedCode.isEmpty()) return
        viewModelScope.launch {
            prefsRepo.savePresetPolicy(
                SitePreset(
                    code = trimmedCode,
                    modeKey = s.mode.key,
                    length = s.length,
                    counter = s.counter,
                    tierKey = s.tier.key
                )
            )
            onSaved()
        }
    }

    fun upsertPreset(preset: SitePreset) {
        viewModelScope.launch { prefsRepo.upsertPreset(preset) }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch { prefsRepo.deletePreset(id) }
    }

    fun incrementPresetRotation(id: String, onRotated: (SitePreset) -> Unit) {
        viewModelScope.launch {
            val updated = prefsRepo.incrementPresetCounter(id)
            if (updated != null) {
                // If this preset's code is currently active in the generator, sync its counter too!
                if (Spv2Spec.ecmaTrim(_uiState.value.code) == updated.code) {
                    onCounterChanged(updated.counter)
                }
                onRotated(updated)
            }
        }
    }

    fun copyCurrentPassword(label: String): Boolean {
        val pwd = _uiState.value.derivedPassword ?: return false
        val clearSec = settingsState.value.clipboardAutoClearSeconds
        return clipboardManager.copySensitivePassword(label, pwd, clearSec)
    }

    fun clearClipboardNow() {
        clipboardManager.clearClipboardNow()
    }

    // Settings updates
    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch { prefsRepo.setTheme(theme) }
    }

    fun cycleTheme() {
        val next = when (settingsState.value.theme) {
            ThemePreference.SYSTEM -> ThemePreference.DARK
            ThemePreference.DARK -> ThemePreference.LIGHT
            ThemePreference.LIGHT -> ThemePreference.SYSTEM
        }
        setTheme(next)
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setDynamicColor(enabled) }
    }

    fun setFlagSecure(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setFlagSecure(enabled) }
    }

    fun setWipeOnBackground(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setWipeOnBackground(enabled) }
    }

    fun setClipboardAutoClearSeconds(seconds: Int) {
        viewModelScope.launch { prefsRepo.setClipboardAutoClearSeconds(seconds) }
    }

    fun setColorizePassword(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setColorizePassword(enabled) }
    }

    fun queryBiometricHardware(): BiometricHardwareStatus = biometricVault.queryHardwareStatus()

    private fun scheduleDerive(debounceMs: Long) {
        deriveJob?.cancel()
        val snapshot = _uiState.value
        val hasMaster = snapshot.master.isNotEmpty()
        val hasCode = Spv2Spec.ecmaTrim(snapshot.code).isNotEmpty()

        if (!hasMaster || !hasCode) {
            deriveSequence++
            _uiState.update {
                it.copy(
                    derivedPassword = null,
                    isDeriving = false,
                    errorCode = null,
                    strength = StrengthEstimate(0, 0, StrengthReason.EMPTY)
                )
            }
            return
        }

        val seq = ++deriveSequence
        deriveJob = viewModelScope.launch {
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            val current = _uiState.value
            val est = CrackResistanceEstimator.estimate(
                master = current.master,
                code = current.code,
                tier = current.tier,
                mode = current.mode,
                length = current.length
            )
            _uiState.update { it.copy(isDeriving = true, errorCode = null, strength = est) }

            try {
                val res = engine.derive(
                    DeriveRequest(
                        master = current.master,
                        code = current.code,
                        tier = current.tier,
                        counter = current.counter,
                        length = current.length,
                        mode = current.mode
                    )
                )
                if (seq == deriveSequence) {
                    _uiState.update {
                        it.copy(
                            derivedPassword = res.password,
                            isDeriving = false,
                            lastCached = res.cached,
                            lastElapsedMs = res.elapsedMs,
                            errorCode = null,
                            strength = est,
                            katVerified = engine.isSelfTestVerified
                        )
                    }
                }
            } catch (e: Spv2Exception) {
                if (seq == deriveSequence) {
                    _uiState.update {
                        it.copy(
                            derivedPassword = null,
                            isDeriving = false,
                            errorCode = e.code
                        )
                    }
                }
            } catch (_: Throwable) {
                if (seq == deriveSequence) {
                    _uiState.update {
                        it.copy(
                            derivedPassword = null,
                            isDeriving = false,
                            errorCode = Spv2ErrorCode.DERIVE_ERROR
                        )
                    }
                }
            }
        }
    }
}
