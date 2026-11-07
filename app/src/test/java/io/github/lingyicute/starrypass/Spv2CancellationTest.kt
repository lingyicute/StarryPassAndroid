package io.github.lingyicute.starrypass

import io.github.lingyicute.starrypass.crypto.DeriveRequest
import io.github.lingyicute.starrypass.crypto.Spv2Engine
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    private val submitted = AtomicInteger()
    private val finished = AtomicInteger()

    // Counts tasks handed to the worker and tasks it has completely run, so "idle" means that
    // every submitted task (including the hand-off of its result to the caller) has finished.
    private fun workerDispatcher() = object : ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        ThreadFactory { task -> Thread(task, "spv2-cancel-test").also { worker = it } }
    ) {
        override fun execute(command: Runnable) {
            submitted.incrementAndGet()
            super.execute(command)
        }

        override fun afterExecute(r: Runnable?, t: Throwable?) {
            finished.incrementAndGet()
            super.afterExecute(r, t)
        }
    }.asCoroutineDispatcher()

    private suspend fun awaitArgon2() = withTimeout(10_000) {
        while (worker?.stackTrace?.none { it.methodName == "argon2id" } != false) delay(1)
    }

    private suspend fun awaitWorkerIdle() = withTimeout(10_000) {
        // Every task submitted so far has run to completion, so its result is already queued
        // for (or discarded by) the caller's dispatcher.
        while (finished.get() < submitted.get()) delay(1)
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

    @Test
    fun `cancelled cache hit after a committed derivation preserves the session cache`() = runBlocking {
        workerDispatcher().use { dispatcher ->
            val engine = Spv2Engine(dispatcher)
            engine.runSelfTest()
            val scheduler = TestCoroutineScheduler()
            val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
            try {
                val initial = DeriveRequest("a private master", "example", length = 16)
                assertFalse(engine.derive(initial).cached)

                val cachedFollowUp = initial.copy(length = 20)
                val pending = caller.async { engine.derive(cachedFollowUp) }
                scheduler.runCurrent()
                awaitWorkerIdle()
                pending.cancel()
                scheduler.advanceUntilIdle()
                assertTrue(pending.isCompleted)

                assertTrue(engine.derive(initial.copy(length = 24)).cached)
            } finally {
                caller.cancel()
            }
        }
    }

    @Test
    fun `cancelling an uncommitted cache hit does not erase the cache while the initial request succeeds`() = runBlocking {
        workerDispatcher().use { dispatcher ->
            val engine = Spv2Engine(dispatcher)
            engine.runSelfTest()
            val scheduler1 = TestCoroutineScheduler()
            val scheduler2 = TestCoroutineScheduler()
            val caller1 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler1))
            val caller2 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler2))
            try {
                val request = DeriveRequest("a private master", "example", length = 16)
                val first = caller1.async { engine.derive(request) }
                scheduler1.runCurrent()
                awaitArgon2()
                awaitWorkerIdle()

                val second = caller2.async { engine.derive(request.copy(length = 20)) }
                scheduler2.runCurrent()
                awaitWorkerIdle()

                // Cancel the second (cache-hit) caller first while the first caller is still pending completion.
                second.cancel()
                scheduler2.advanceUntilIdle()
                assertTrue(second.isCompleted)

                // Now let the first caller complete normally; the session cache must remain committed.
                scheduler1.advanceUntilIdle()
                assertFalse(first.await().cached)
                assertTrue(engine.derive(request.copy(length = 24)).cached)
            } finally {
                caller1.cancel()
                caller2.cancel()
            }
        }
    }

    @Test
    fun `cancelling a new site derivation during Argon2 preserves the previously committed cache`() = runBlocking {
        workerDispatcher().use { dispatcher ->
            val engine = Spv2Engine(dispatcher)
            engine.runSelfTest()
            val committed = DeriveRequest("a private master", "alpha", length = 16)
            assertFalse(engine.derive(committed).cached)

            val otherSite = DeriveRequest("a private master", "beta", length = 16)
            val pending = async { engine.derive(otherSite) }
            awaitArgon2()
            pending.cancelAndJoin()

            assertTrue(engine.derive(committed.copy(length = 20)).cached)
        }
    }
}
