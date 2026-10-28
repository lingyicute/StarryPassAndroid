/*
 * StarryPass Android — Session wipe generation
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** A synchronous generation check also rejects callbacks that arrive before collectors run. */
class SessionWipeSignal {
    private val mutableGeneration = MutableStateFlow(0L)
    val generation: StateFlow<Long> = mutableGeneration.asStateFlow()

    fun invalidate(): Long = mutableGeneration.updateAndGet { it + 1 }
    fun isCurrent(token: Long): Boolean = generation.value == token
}
