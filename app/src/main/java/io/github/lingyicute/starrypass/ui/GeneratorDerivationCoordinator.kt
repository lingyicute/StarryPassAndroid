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

    fun cancel() {
        job?.cancel()
        job = null
        sequence++
    }

    fun updateInputs(debounceMs: Long, change: (GeneratorUiState) -> GeneratorUiState) {
        cancel()
        val seq = sequence
        val wipeGeneration = currentWipeGeneration()
        // Change the inputs and invalidate the previous output in one state update.
        val snapshot = state.updateAndGet { previous ->
            val next = change(previous)
            val ready = next.master.isNotEmpty() && Spv2Spec.ecmaTrim(next.code).isNotEmpty()
            val strength = if (ready) {
                CrackResistanceEstimator.estimate(next.master, next.code, next.tier, next.mode, next.length)
            } else {
                StrengthEstimate(0, 0, StrengthReason.EMPTY)
            }
            next.copy(derivedPassword = null, isDeriving = ready, errorCode = null, strength = strength)
        }
        if (!snapshot.isDeriving) return

        job = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
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
