package io.github.lingyicute.starrypass

import io.github.lingyicute.starrypass.crypto.DeriveResult
import io.github.lingyicute.starrypass.security.SessionWipeSignal
import io.github.lingyicute.starrypass.ui.GeneratorDerivationCoordinator
import io.github.lingyicute.starrypass.ui.GeneratorUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GeneratorDerivationCoordinatorTest {
    @Test
    fun `new input clears the old password during debounce and ignores a late result`() = runTest {
        val state = MutableStateFlow(GeneratorUiState(master = "master", code = "site", derivedPassword = "old"))
        val gates = mutableMapOf<Int, CompletableDeferred<DeriveResult>>()
        val coordinator = GeneratorDerivationCoordinator(
            scope = this,
            state = state,
            derive = { request ->
                val gate = gates.getOrPut(request.length) { CompletableDeferred() }
                // Deliberately simulate a computation that ignores cancellation and returns late.
                try { gate.await() } catch (_: CancellationException) { withContext(NonCancellable) { gate.await() } }
            },
            isSelfTestVerified = { true }
        )

        coordinator.updateInputs(280) { it.copy(length = 17) }
        assertNull(state.value.derivedPassword)
        assertTrue(state.value.isDeriving)
        advanceTimeBy(280)
        runCurrent()
        assertTrue(gates.containsKey(17))

        coordinator.updateInputs(0) { it.copy(length = 18) }
        assertNull(state.value.derivedPassword)
        assertTrue(state.value.isDeriving)
        runCurrent()
        gates.getValue(17).complete(DeriveResult("stale", false, 1))
        runCurrent()
        assertNull(state.value.derivedPassword)
        assertTrue(state.value.isDeriving)

        gates.getValue(18).complete(DeriveResult("current", false, 2))
        runCurrent()
        assertEquals("current", state.value.derivedPassword)
        assertFalse(state.value.isDeriving)
        assertTrue(state.value.katVerified)
    }

    @Test
    fun `wipe generation blocks a result before UI collectors have run`() = runTest {
        val signal = SessionWipeSignal()
        val oldToken = signal.generation.value
        val gate = CompletableDeferred<DeriveResult>()
        val state = MutableStateFlow(GeneratorUiState(master = "master", code = "site"))
        val coordinator = GeneratorDerivationCoordinator(
            scope = this,
            state = state,
            derive = { gate.await() },
            isSelfTestVerified = { true },
            currentWipeGeneration = { signal.generation.value }
        )
        coordinator.updateInputs(0) { it.copy(length = 18) }
        runCurrent()
        signal.invalidate()
        assertFalse(signal.isCurrent(oldToken))
        assertTrue(signal.isCurrent(signal.generation.value))
        gate.complete(DeriveResult("late secret", false, 1))
        runCurrent()
        assertNull(state.value.derivedPassword)
    }

    @Test
    fun `empty input clears the old result without scheduling a derivation`() = runTest {
        var called = false
        val state = MutableStateFlow(GeneratorUiState(master = "master", code = "site", derivedPassword = "old"))
        val coordinator = GeneratorDerivationCoordinator(
            scope = this,
            state = state,
            derive = { called = true; DeriveResult("unexpected", false, 0) },
            isSelfTestVerified = { false }
        )
        coordinator.updateInputs(0) { it.copy(code = "   ") }
        runCurrent()
        assertNull(state.value.derivedPassword)
        assertFalse(state.value.isDeriving)
        assertFalse(called)
    }
}
