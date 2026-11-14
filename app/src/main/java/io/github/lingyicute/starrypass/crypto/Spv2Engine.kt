/*
 * StarryPass Android — Core Deterministic Derivation Engine (v2.1 Production Parity)
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DeriveRequest(
    val master: String,
    val code: String,
    val tier: Spv2Tier = Spv2Tier.STANDARD,
    val counter: Int = Spv2Spec.DEFAULT_COUNTER,
    val length: Int = Spv2Spec.DEFAULT_LENGTH,
    val mode: Spv2Mode = Spv2Mode.FULL
) {
    companion object {
        /**
         * Permissive factory matching upstream JS `canonicalizeInputs(req)` for raw string/nullable inputs.
         */
        fun fromRaw(
            master: String?,
            code: String?,
            tier: String?,
            counter: Int?,
            length: Int?,
            mode: String?
        ): DeriveRequest = DeriveRequest(
            master = master ?: "",
            code = code ?: "",
            tier = Spv2Tier.fromKey(tier),
            counter = Spv2Spec.clampCounter(counter),
            length = Spv2Spec.clampLength(length),
            mode = Spv2Mode.fromKey(mode)
        )
    }
}

data class DeriveResult(
    val password: String,
    val cached: Boolean,
    val elapsedMs: Long
)

/**
 * Thread-safe, coroutine-friendly StarryPass v2.1 engine.
 *
 * All `derive` and `wipeSession` calls are strictly serialised through a single [Mutex],
 * mirroring upstream's single-Promise queue so `wipeSession()` can never race with an
 * in-flight derivation or leave stale session keys behind.
 */
class Spv2Engine(
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val mutex = Mutex()

    @Volatile
    private var selfTestPassed = false

    private var sessionNonce: ByteArray? = null
    private var cachedTag: ByteArray? = null
    private var cachedSk: ByteArray? = null

    private val cacheStateLock = Any()
    private var cacheCommitted = false
    private val uncommittedOwners = HashSet<Any>()

    val isSelfTestVerified: Boolean
        get() = selfTestPassed

    /**
     * Runs FIPS 140-3 style power-on Known-Answer Tests (KAT):
     *   1. RFC 9106 §5.3 Argon2id KAT (with both secret `K` and associated data `X`)
     *   2. RFC 5869 Test Case 1 HMAC-SHA-256 (PRK) & HKDF-SHA-256 (42-byte OKM) KAT
     */
    suspend fun runSelfTest(): Boolean = withContext(computeDispatcher) {
        mutex.withLock {
            runSelfTestLocked()
            true
        }
    }

    suspend fun derive(request: DeriveRequest): DeriveResult {
        val owner = Any()
        try {
            val result = withContext(computeDispatcher) {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    val t0 = System.nanoTime()
                    runSelfTestLocked()
                    currentCoroutineContext().ensureActive()

                    val masterBytes = Spv2Spec.normalizeMasterToUtf8(request.master)
                    val codeBytes = try {
                        Spv2Spec.normalizeCodeToUtf8(request.code)
                    } catch (t: Throwable) {
                        Spv2Spec.zeroize(masterBytes)
                        throw t
                    }
                    val counter = Spv2Spec.clampCounter(request.counter)
                    val length = Spv2Spec.clampLength(request.length)
                    val tier = request.tier
                    val mode = request.mode

                    try {
                        if (masterBytes.isEmpty() || codeBytes.isEmpty()) {
                            throw Spv2Exception(Spv2ErrorCode.EMPTY_INPUT)
                        }
                        val reqTag = computeSessionTagLocked(masterBytes, codeBytes, tier.id)
                        var tagTransferred = false
                        try {
                            val existingTag = cachedTag
                            val existingSk = cachedSk
                            val usedCache = existingTag != null && existingSk != null &&
                                Spv2Primitives.constantTimeEquals(reqTag, existingTag)

                            if (usedCache) {
                                currentCoroutineContext().ensureActive()
                                Spv2Spec.zeroize(masterBytes)
                                val pwd = synthesizePassword(existingSk!!, codeBytes, counter, length, mode)
                                currentCoroutineContext().ensureActive()
                                // Track every in-flight caller using an uncommitted cache entry.
                                synchronized(cacheStateLock) {
                                    if (!cacheCommitted) {
                                        uncommittedOwners.add(owner)
                                    }
                                }
                                val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
                                DeriveResult(password = pwd, cached = true, elapsedMs = elapsedMs)
                            } else {
                                var salt: ByteArray? = null
                                var ad: ByteArray? = null
                                val fresh = try {
                                    salt = buildSalt(codeBytes, tier.id)
                                    ad = Spv2Spec.domainAd()
                                    // Bouncy Castle Argon2 is synchronous and cannot be interrupted.
                                    Spv2Primitives.argon2id(
                                        password = masterBytes,
                                        salt = salt,
                                        ad = ad,
                                        secret = null,
                                        memoryKiB = tier.memoryKiB,
                                        passes = tier.passes,
                                        parallelism = tier.parallelism,
                                        tagLength = Spv2Spec.SESSION_KEY_BYTES
                                    )
                                } finally {
                                    Spv2Spec.zeroize(salt)
                                    Spv2Spec.zeroize(ad)
                                }
                                try {
                                    // Do not publish work that finished after its caller was cancelled.
                                    currentCoroutineContext().ensureActive()
                                    Spv2Spec.zeroize(masterBytes)
                                    val pwd = synthesizePassword(fresh, codeBytes, counter, length, mode)
                                    currentCoroutineContext().ensureActive()
                                    clearCachedKeyLocked()
                                    cachedSk = fresh
                                    cachedTag = reqTag
                                    synchronized(cacheStateLock) {
                                        uncommittedOwners.add(owner)
                                    }
                                    tagTransferred = true
                                    val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
                                    DeriveResult(password = pwd, cached = false, elapsedMs = elapsedMs)
                                } finally {
                                    if (!tagTransferred) Spv2Spec.zeroize(fresh)
                                }
                            }
                        } finally {
                            if (!tagTransferred) Spv2Spec.zeroize(reqTag)
                        }
                    } finally {
                        Spv2Spec.zeroize(masterBytes)
                        Spv2Spec.zeroize(codeBytes)
                    }
                }
            }
            synchronized(cacheStateLock) {
                if (uncommittedOwners.remove(owner)) {
                    cacheCommitted = true
                    uncommittedOwners.clear()
                }
            }
            return result
        } catch (cancelled: CancellationException) {
            // withContext can discard an already computed result when dispatching back to a
            // cancelled caller. Only clear this request's uncommitted cache when no caller
            // has committed it and no other uncommitted caller is still in flight.
            withContext(NonCancellable) {
                mutex.withLock {
                    val shouldClear = synchronized(cacheStateLock) {
                        uncommittedOwners.remove(owner) && !cacheCommitted && uncommittedOwners.isEmpty()
                    }
                    if (shouldClear) clearCachedKeyLocked()
                }
            }
            throw cancelled
        }
    }

    /**
     * Zeroizes cached session key, HMAC tag, and per-session CSPRNG nonce.
     * Runs to completion once started: a caller cancelled while waiting for an in-flight
     * Argon2 run to release the mutex must not leave the key material behind.
     */
    suspend fun wipeSession() {
        withContext(NonCancellable) {
            mutex.withLock {
                clearCachedKeyLocked()
                Spv2Spec.zeroize(sessionNonce)
                sessionNonce = null
            }
        }
    }

    private fun clearCachedKeyLocked() {
        Spv2Spec.zeroize(cachedTag)
        cachedTag = null
        Spv2Spec.zeroize(cachedSk)
        cachedSk = null
        synchronized(cacheStateLock) {
            cacheCommitted = false
            uncommittedOwners.clear()
        }
    }

    private fun getOrCreateSessionNonceLocked(): ByteArray {
        var nonce = sessionNonce
        if (nonce == null) {
            nonce = Spv2Primitives.randomBytes(Spv2Spec.SESSION_NONCE_BYTES)
            sessionNonce = nonce
        }
        return nonce
    }

    private fun buildSalt(codeBytes: ByteArray, tierId: Byte): ByteArray {
        var domain: ByteArray? = null
        var prefixedCode: ByteArray? = null
        var saltIn: ByteArray? = null
        return try {
            domain = Spv2Spec.domainSalt()
            prefixedCode = Spv2Spec.lenPrefixed(codeBytes)
            saltIn = Spv2Spec.concatBytes(domain, prefixedCode, byteArrayOf(tierId))
            Spv2Primitives.sha256(saltIn)
        } finally {
            Spv2Spec.zeroize(domain)
            Spv2Spec.zeroize(prefixedCode)
            Spv2Spec.zeroize(saltIn)
        }
    }

    private fun computeSessionTagLocked(
        masterBytes: ByteArray,
        codeBytes: ByteArray,
        tierId: Byte
    ): ByteArray {
        var domain: ByteArray? = null
        var prefixedMaster: ByteArray? = null
        var prefixedCode: ByteArray? = null
        var msg: ByteArray? = null
        return try {
            domain = Spv2Spec.domainCache()
            prefixedMaster = Spv2Spec.lenPrefixed(masterBytes)
            prefixedCode = Spv2Spec.lenPrefixed(codeBytes)
            msg = Spv2Spec.concatBytes(domain, prefixedMaster, prefixedCode, byteArrayOf(tierId))
            Spv2Primitives.hmacSha256(getOrCreateSessionNonceLocked(), msg)
        } finally {
            // Allocated inside the try so a failure part-way still wipes what already exists.
            Spv2Spec.zeroize(domain)
            Spv2Spec.zeroize(prefixedMaster)
            Spv2Spec.zeroize(prefixedCode)
            Spv2Spec.zeroize(msg)
        }
    }

    private fun synthesizePassword(
        sk: ByteArray,
        codeBytes: ByteArray,
        counter: Int,
        length: Int,
        mode: Spv2Mode
    ): String {
        var meta: ByteArray? = null
        var domainExpand: ByteArray? = null
        var domainExtract: ByteArray? = null
        var prefixedCode: ByteArray? = null
        var info: ByteArray? = null
        var stream: Spv2Primitives.HkdfStream? = null
        try {
            meta = ByteArray(7).also {
                Spv2Spec.writeLE32(it, 0, counter)
                Spv2Spec.writeLE16(it, 4, length and 0xFFFF)
                it[6] = mode.id
            }
            domainExpand = Spv2Spec.domainExpand()
            domainExtract = Spv2Spec.domainExtract()
            prefixedCode = Spv2Spec.lenPrefixed(codeBytes)
            info = Spv2Spec.concatBytes(domainExpand, meta, prefixedCode)
            stream = Spv2Primitives.HkdfStream(sk = sk, salt = domainExtract, info = info)

            val pool = mode.pool
            val reqClasses = if (length >= mode.requiredClasses.size) mode.requiredClasses else emptyList()
            var result: String? = null

            val chars = CharArray(length)
            try {
                for (attempt in 0 until Spv2Spec.REJECTION_MAX_ATTEMPTS) {
                    for (i in 0 until length) {
                        chars[i] = pool[stream.uniformInt(pool.length)]
                    }

                    var ok = true
                    for (cls in reqClasses) {
                        var found = false
                        for (k in 0 until length) {
                            if (cls.indexOf(chars[k]) != -1) {
                                found = true
                                break
                            }
                        }
                        if (!found) {
                            ok = false
                            break
                        }
                    }
                    if (ok) {
                        result = String(chars)
                        break
                    }
                }
            } finally {
                Spv2Spec.zeroize(chars)
            }

            if (result == null) {
                // Deterministic Fisher-Yates fallback identical to upstream spv2-core.mjs
                val arr = CharArray(length)
                result = try {
                    var idx = 0
                    for (cls in reqClasses) {
                        arr[idx++] = cls[stream.uniformInt(cls.length)]
                    }
                    while (idx < length) {
                        arr[idx++] = pool[stream.uniformInt(pool.length)]
                    }
                    for (s in length - 1 downTo 1) {
                        val j = stream.uniformInt(s + 1)
                        val tmp = arr[s]
                        arr[s] = arr[j]
                        arr[j] = tmp
                    }
                    String(arr)
                } finally {
                    Spv2Spec.zeroize(arr)
                }
            }

            return result
        } finally {
            Spv2Spec.zeroize(meta)
            Spv2Spec.zeroize(domainExpand)
            Spv2Spec.zeroize(domainExtract)
            Spv2Spec.zeroize(prefixedCode)
            Spv2Spec.zeroize(info)
            stream?.dispose()
        }
    }

    private fun runSelfTestLocked() {
        if (selfTestPassed) return

        // 1. RFC 9106 §5.3 Argon2id KAT (exercises password + salt + secret + associated data)
        val p = ByteArray(32) { 1 }
        val s = ByteArray(16) { 2 }
        val k = ByteArray(8) { 3 }
        val x = ByteArray(12) { 4 }
        var tagK: ByteArray? = null
        val a2Ok = try {
            tagK = Spv2Primitives.argon2id(
                password = p,
                salt = s,
                ad = x,
                secret = k,
                memoryKiB = 32,
                passes = 3,
                parallelism = 4,
                tagLength = 32
            )
            val expectedA2 = byteArrayOf(
                0x0d, 0x64, 0x0d, 0xf5.toByte(), 0x8d.toByte(), 0x78, 0x76, 0x6c,
                0x08, 0xc0.toByte(), 0x37, 0xa3.toByte(), 0x4a, 0x8b.toByte(), 0x53, 0xc9.toByte(),
                0xd0.toByte(), 0x1e, 0xf0.toByte(), 0x45, 0x2d, 0x75, 0xb6.toByte(), 0x5e,
                0xb5.toByte(), 0x25, 0x20, 0xe9.toByte(), 0x6b, 0x01, 0xe6.toByte(), 0x59
            )
            Spv2Primitives.constantTimeEquals(tagK, expectedA2)
        } finally {
            Spv2Spec.zeroize(p)
            Spv2Spec.zeroize(s)
            Spv2Spec.zeroize(k)
            Spv2Spec.zeroize(x)
            Spv2Spec.zeroize(tagK)
        }
        if (!a2Ok) {
            throw Spv2Exception(Spv2ErrorCode.KAT_ARGON2ID_FAILED)
        }

        // 2. RFC 5869 Test Case 1 HMAC-SHA-256 PRK KAT + full 42-byte HKDF OKM KAT
        val tcSalt = ByteArray(13) { it.toByte() }
        val tcIkm = ByteArray(22) { 0x0b }
        val tcInfo = byteArrayOf(
            0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(),
            0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte()
        )
        var prk: ByteArray? = null
        var okm: ByteArray? = null
        var stream: Spv2Primitives.HkdfStream? = null
        try {
            prk = Spv2Primitives.hmacSha256(tcSalt, tcIkm)
            val expectedPrk8 = byteArrayOf(0x07, 0x77, 0x09, 0x36, 0x2c, 0x2e, 0x32, 0xdf.toByte())
            for (j in 0 until 8) {
                if (prk[j] != expectedPrk8[j]) {
                    throw Spv2Exception(Spv2ErrorCode.KAT_SHA256_FAILED)
                }
            }

            stream = Spv2Primitives.HkdfStream(sk = tcIkm, salt = tcSalt, info = tcInfo)
            okm = ByteArray(42) { stream.nextUnsignedByte().toByte() }
            val expectedOkm = byteArrayOf(
                0x3c, 0xb2.toByte(), 0x5f, 0x25, 0xfa.toByte(), 0xac.toByte(), 0xd5.toByte(), 0x7a,
                0x90.toByte(), 0x43, 0x4f, 0x64, 0xd0.toByte(), 0x36, 0x2f, 0x2a,
                0x2d, 0x2d, 0x0a, 0x90.toByte(), 0xcf.toByte(), 0x1a, 0x5a, 0x4c,
                0x5d, 0xb0.toByte(), 0x2d, 0x56, 0xec.toByte(), 0xc4.toByte(), 0xc5.toByte(), 0xbf.toByte(),
                0x34, 0x00, 0x72, 0x08, 0xd5.toByte(), 0xb8.toByte(), 0x87.toByte(), 0x18,
                0x58, 0x65
            )
            if (!Spv2Primitives.constantTimeEquals(okm, expectedOkm)) {
                throw Spv2Exception(Spv2ErrorCode.KAT_HKDF_FAILED)
            }
        } finally {
            stream?.dispose()
            Spv2Spec.zeroize(tcSalt)
            Spv2Spec.zeroize(tcIkm)
            Spv2Spec.zeroize(tcInfo)
            Spv2Spec.zeroize(prk)
            Spv2Spec.zeroize(okm)
        }

        selfTestPassed = true
    }
}
