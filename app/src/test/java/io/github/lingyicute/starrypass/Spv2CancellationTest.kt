package io.github.lingyicute.starrypass

import io.github.lingyicute.starrypass.crypto.DeriveRequest
import io.github.lingyicute.starrypass.crypto.Spv2Engine
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Spv2CancellationTest {
    @Volatile private var worker: Thread? = null

    private fun workerDispatcher() = Executors.newSingleThreadExecutor { task ->
        Thread(task, "spv2-cancel-test").also { worker = it }
    }.asCoroutineDispatcher()

    private suspend fun awaitArgon2() = withTimeout(10_000) {
        while (worker?.stackTrace?.none { it.methodName == "argon2id" } != false) delay(1)
    }

    private suspend fun awaitWorkerIdle() = withTimeout(10_000) {
        // Called only after observing Argon2, so WAITING means the completed result was dispatched.
        while (worker?.state != Thread.State.WAITING) delay(1)
    }

    @Test
    fun `cancellation during Argon2 does not leave a reusable session key`() = runBlocking {
        workerDispatcher().use { dispatcher ->
            val engine = Spv2Engine(dispatcher)
            engine.runSelfTest()
            val request = DeriveRequest("a private master", "example")
            val pending = async { engine.derive(request) }
            awaitArgon2()
            pending.cancelAndJoin()
            assertFalse(engine.derive(request).cached)
            assertTrue(engine.derive(request).cached)
        }
    }

    @Test
    fun `cancellation after computation clears only the cancelled request cache`() = runBlocking {
        workerDispatcher().use { dispatcher ->
            val engine = Spv2Engine(dispatcher)
            engine.runSelfTest()
            val scheduler = TestCoroutineScheduler()
            val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
            try {
                val request = DeriveRequest("a private master", "example")
                val pending = caller.async { engine.derive(request) }
                scheduler.runCurrent()
                awaitArgon2()
                awaitWorkerIdle()
                pending.cancel()
                scheduler.advanceUntilIdle()
                assertTrue(pending.isCompleted)
                assertFalse(engine.derive(request).cached)
            } finally {
                caller.cancel()
            }
        }
    }

    @Test
    fun `late cancellation cannot erase a cache reused by a newer request`() = runBlocking {
        workerDispatcher().use { dispatcher ->
            val engine = Spv2Engine(dispatcher)
            engine.runSelfTest()
            val scheduler = TestCoroutineScheduler()
            val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
            try {
                val request = DeriveRequest("a private master", "example")
                val pending = caller.async { engine.derive(request) }
                scheduler.runCurrent()
                awaitArgon2()
                awaitWorkerIdle()
                assertTrue(engine.derive(request).cached)
                pending.cancel()
                scheduler.advanceUntilIdle()
                assertTrue(pending.isCompleted)
                assertTrue(engine.derive(request).cached)
            } finally {
                caller.cancel()
            }
        }
    }
}
