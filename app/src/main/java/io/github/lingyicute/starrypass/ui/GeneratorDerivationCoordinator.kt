/*
 * StarryPass Android — Generator derivation state coordination
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.ui

import io.github.lingyicute.starrypass.crypto.CrackResistanceEstimator
import io.github.lingyicute.starrypass.crypto.DeriveRequest
import io.github.lingyicute.starrypass.crypto.DeriveResult
import io.github.lingyicute.starrypass.crypto.Spv2ErrorCode
import io.github.lingyicute.starrypass.crypto.Spv2Exception
import io.github.lingyicute.starrypass.crypto.Spv2Mode
import io.github.lingyicute.starrypass.crypto.Spv2Spec
import io.github.lingyicute.starrypass.crypto.StrengthEstimate
import io.github.lingyicute.starrypass.crypto.StrengthReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.text.Normalizer

/** Called on the UI thread; every result is tied to the input snapshot that produced it. */
internal class GeneratorDerivationCoordinator(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<GeneratorUiState>,
    private val derive: suspend (DeriveRequest) -> DeriveResult,
    private val isSelfTestVerified: () -> Boolean,
    private val currentWipeGeneration: () -> Long = { 0L }
) {
    private var job: Job? = null
    private var sequence = 0L
    private var activeDebounceMs = 0L

    fun cancel() {
        job?.cancel()
        job = null
        activeDebounceMs = 0L
        sequence++
    }

    private fun canonicalMaster(master: String): String =
        Normalizer.normalize(master, Normalizer.Form.NFC)

    private fun canonicalCode(code: String): String =
        Normalizer.normalize(Spv2Spec.ecmaTrim(code), Normalizer.Form.NFC)

    private fun sameDeriveInputs(a: GeneratorUiState, b: GeneratorUiState): Boolean =
        canonicalMaster(a.master) == canonicalMaster(b.master) &&
            canonicalCode(a.code) == canonicalCode(b.code) &&
            a.length == b.length &&
            a.mode == b.mode &&
            a.counter == b.counter &&
            a.tier == b.tier

    private fun isShortPin(s: GeneratorUiState): Boolean =
        s.mode == Spv2Mode.PIN && Spv2Spec.clampLength(s.length) <= 8

    private fun sameStrengthInputs(a: GeneratorUiState, b: GeneratorUiState): Boolean =
        canonicalMaster(a.master) == canonicalMaster(b.master) &&
            canonicalCode(a.code) == canonicalCode(b.code) &&
            a.tier == b.tier &&
            a.mode == b.mode &&
            isShortPin(a) == isShortPin(b)

    fun updateInputs(debounceMs: Long, change: (GeneratorUiState) -> GeneratorUiState) {
        val current = state.value
        val candidate = change(current)
        if (sameDeriveInputs(current, candidate)) {
            val ready = candidate.master.isNotEmpty() && Spv2Spec.ecmaTrim(candidate.code).isNotEmpty()
            val canKeepState = current.derivedPassword != null ||
                (current.isDeriving && job?.isActive == true && debounceMs >= activeDebounceMs) ||
                (!ready && !current.isDeriving && current.errorCode == null)
            if (canKeepState) {
                if (current != candidate) {
                    state.update { prev ->
                        val updated = change(prev)
                        if (sameDeriveInputs(prev, updated)) {
                            updated.copy(
                                derivedPassword = prev.derivedPassword,
                                isDeriving = prev.isDeriving,
                                lastCached = prev.lastCached,
                                lastElapsedMs = prev.lastElapsedMs,
                                errorCode = prev.errorCode,
                                strength = prev.strength,
                                katVerified = prev.katVerified
                            )
                        } else {
                            prev
                        }
                    }
                }
                return
            }
        }

        cancel()
        val seq = sequence
        val wipeGeneration = currentWipeGeneration()
        activeDebounceMs = debounceMs
        // Change the inputs and invalidate the previous output in one state update.
        val snapshot = state.updateAndGet { previous ->
            val next = change(previous)
            val ready = next.master.isNotEmpty() && Spv2Spec.ecmaTrim(next.code).isNotEmpty()
            val strength = when {
                !ready -> StrengthEstimate(0, 0, StrengthReason.EMPTY)
                // Counter and most length changes cannot alter the estimate; skip the UI-thread rescoring.
                previous.strength.reason != StrengthReason.EMPTY && sameStrengthInputs(previous, next) ->
                    previous.strength
                else ->
                    CrackResistanceEstimator.estimate(next.master, next.code, next.tier, next.mode, next.length)
            }
            next.copy(
                derivedPassword = null,
                isDeriving = ready,
                lastCached = false,
                lastElapsedMs = 0L,
                errorCode = null,
                strength = strength
            )
        }
        if (!snapshot.isDeriving) return

        job = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            if (seq == sequence) {
                activeDebounceMs = 0L
            }
            try {
                val result = derive(
                    DeriveRequest(
                        master = snapshot.master,
                        code = snapshot.code,
                        tier = snapshot.tier,
                        counter = snapshot.counter,
                        length = snapshot.length,
                        mode = snapshot.mode
                    )
                )
                if (seq == sequence && wipeGeneration == currentWipeGeneration()) {
                    state.update {
                        it.copy(
                            derivedPassword = result.password,
                            isDeriving = false,
                            lastCached = result.cached,
                            lastElapsedMs = result.elapsedMs,
                            errorCode = null,
                            katVerified = isSelfTestVerified()
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Spv2Exception) {
                if (seq == sequence && wipeGeneration == currentWipeGeneration()) {
                    state.update { it.copy(derivedPassword = null, isDeriving = false, errorCode = e.code) }
                }
            } catch (_: OutOfMemoryError) {
                if (seq == sequence && wipeGeneration == currentWipeGeneration()) {
                    state.update {
                        it.copy(derivedPassword = null, isDeriving = false, errorCode = Spv2ErrorCode.OUT_OF_MEMORY)
                    }
                }
            } catch (_: Throwable) {
                if (seq == sequence && wipeGeneration == currentWipeGeneration()) {
                    state.update {
                        it.copy(derivedPassword = null, isDeriving = false, errorCode = Spv2ErrorCode.DERIVE_ERROR)
                    }
                }
            }
        }
    }
}
