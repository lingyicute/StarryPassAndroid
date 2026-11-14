package io.github.lingyicute.starrypass

import io.github.lingyicute.starrypass.crypto.DeriveResult
import io.github.lingyicute.starrypass.crypto.Spv2ErrorCode
import io.github.lingyicute.starrypass.crypto.Spv2Mode
import io.github.lingyicute.starrypass.crypto.StrengthReason
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test
    fun `raw OutOfMemoryError maps to OUT_OF_MEMORY error code`() = runTest {
        val state = MutableStateFlow(GeneratorUiState(master = "master", code = "site"))
        val coordinator = GeneratorDerivationCoordinator(
            scope = this,
            state = state,
            derive = { throw OutOfMemoryError("heap exhausted") },
            isSelfTestVerified = { true }
        )
        coordinator.updateInputs(0) { it.copy(length = 20) }
        runCurrent()
        assertNull(state.value.derivedPassword)
        assertFalse(state.value.isDeriving)
        assertEquals(Spv2ErrorCode.OUT_OF_MEMORY, state.value.errorCode)
    }

    @Test
    fun `unchanged inputs preserve derived password and do not restart active derivation`() = runTest {
        var calls = 0
        val gate = CompletableDeferred<DeriveResult>()
        val state = MutableStateFlow(GeneratorUiState(master = "master", code = "site"))
        val coordinator = GeneratorDerivationCoordinator(
            scope = this,
            state = state,
            derive = {
                calls++
                gate.await()
            },
            isSelfTestVerified = { true }
        )

        coordinator.updateInputs(280) { it.copy(length = 20) }
        advanceTimeBy(200)
        // Sub-integer slider movement rounding to the same length must not reset debounce or re-derive.
        coordinator.updateInputs(280) { it.copy(length = 20) }
        advanceTimeBy(80)
        runCurrent()
        assertEquals(1, calls)

        // Once debounce has finished and derive is running, an immediate equivalent update must not restart it.
        coordinator.updateInputs(0) { it.copy(code = " site ") }
        runCurrent()
        assertEquals(1, calls)
        assertEquals(" site ", state.value.code)

        gate.complete(DeriveResult("derived-20", false, 5))
        runCurrent()
        assertEquals("derived-20", state.value.derivedPassword)

        // Re-selecting the same length or policy keeps the existing derived password without re-running derive.
        coordinator.updateInputs(0) { it.copy(length = 20) }
        runCurrent()
        assertEquals(1, calls)
        assertEquals("derived-20", state.value.derivedPassword)
    }

    @Test
    fun `strength estimate is reused when only counter or irrelevant length changes`() = runTest {
        val state = MutableStateFlow(GeneratorUiState(master = "Tr0ub4dor&3-xyz!Qw", code = "site"))
        val coordinator = GeneratorDerivationCoordinator(
            scope = this,
            state = state,
            derive = { DeriveResult("derived", false, 1) },
            isSelfTestVerified = { true }
        )

        coordinator.updateInputs(0) { it.copy(length = 17) }
        val first = state.value.strength
        assertNotEquals(StrengthReason.EMPTY, first.reason)

        coordinator.updateInputs(0) { it.copy(counter = 2) }
        assertSame(first, state.value.strength)
        coordinator.updateInputs(0) { it.copy(length = 20) }
        assertSame(first, state.value.strength)

        coordinator.updateInputs(0) { it.copy(master = "Tr0ub4dor&3-xyz!Qw?") }
        assertNotSame(first, state.value.strength)
        runCurrent()
    }

    @Test
    fun `strength estimate is recomputed when a PIN crosses the short-length boundary`() = runTest {
        val state = MutableStateFlow(
            GeneratorUiState(master = "Tr0ub4dor&3-xyz!Qw", code = "site", mode = Spv2Mode.PIN, length = 6)
        )
        val coordinator = GeneratorDerivationCoordinator(
            scope = this,
            state = state,
            derive = { DeriveResult("123456", false, 1) },
            isSelfTestVerified = { true }
        )

        coordinator.updateInputs(0) { it.copy(counter = 2) }
        val shortPin = state.value.strength
        coordinator.updateInputs(0) { it.copy(length = 8) }
        assertSame(shortPin, state.value.strength)

        coordinator.updateInputs(0) { it.copy(length = 12) }
        assertNotEquals(shortPin.reason, state.value.strength.reason)
        runCurrent()
    }
}
