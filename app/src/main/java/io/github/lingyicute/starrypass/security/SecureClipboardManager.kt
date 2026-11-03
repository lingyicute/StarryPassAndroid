/*
 * StarryPass Android — Sensitive Clipboard Manager with Scheduled Auto-Wipe
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import io.github.lingyicute.starrypass.crypto.Spv2Primitives
import io.github.lingyicute.starrypass.crypto.Spv2Spec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SecureClipboardManager(context: Context) {

    private val clipboard = context.applicationContext
        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var clearJob: Job? = null
    private var lastCopiedSha256: ByteArray? = null

    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

    /**
     * Copies [secret] marked as sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`) so Android 13+
     * hides the clipboard preview popup, and schedules automatic clearing after [autoClearSeconds].
     */
    fun copySensitivePassword(label: String, secret: String, autoClearSeconds: Int): Boolean {
        return try {
            val clip = ClipData.newPlainText(label, secret)
            val extras = PersistableBundle().apply {
                val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ClipDescription.EXTRA_IS_SENSITIVE
                } else {
                    "android.content.extra.IS_SENSITIVE"
                }
                putBoolean(key, true)
            }
            clip.description.extras = extras
            clipboard.setPrimaryClip(clip)

            val secretBytes = secret.toByteArray(Charsets.UTF_8)
            val digest = Spv2Primitives.sha256(secretBytes)
            Spv2Spec.zeroize(secretBytes)
            Spv2Spec.zeroize(lastCopiedSha256)
            lastCopiedSha256 = digest

            scheduleAutoClear(autoClearSeconds)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun clearClipboardNow() {
        clearJob?.cancel()
        clearJob = null
        _remainingSeconds.value = 0
        // Same rule as the timer: do not destroy something the user copied after the password.
        val expectedHash = lastCopiedSha256
        if (expectedHash == null || shouldWipeNow(expectedHash)) {
            wipeSystemClipboard()
        }
        Spv2Spec.zeroize(lastCopiedSha256)
        lastCopiedSha256 = null
    }

    fun onAutoClearTimeoutChanged(seconds: Int) {
        if (seconds <= 0) {
            clearJob?.cancel()
            clearJob = null
            _remainingSeconds.value = 0
            return
        }
        if (clearJob?.isActive == true) {
            if (_remainingSeconds.value > seconds) {
                scheduleAutoClear(seconds)
            }
        } else if (lastCopiedSha256 != null) {
            scheduleAutoClear(seconds)
        }
    }

    private fun scheduleAutoClear(seconds: Int) {
        clearJob?.cancel()
        clearJob = null
        if (seconds <= 0) {
            _remainingSeconds.value = 0
            return
        }
        clearJob = scope.launch {
            var rem = seconds
            _remainingSeconds.value = rem
            while (rem > 0) {
                delay(1_000L)
                rem--
                _remainingSeconds.value = rem
            }
            // Clear unless the clipboard is readable and now holds something else
            val expectedHash = lastCopiedSha256
            if (expectedHash != null && shouldWipeNow(expectedHash)) {
                wipeSystemClipboard()
            }
            Spv2Spec.zeroize(lastCopiedSha256)
            lastCopiedSha256 = null
        }
    }

    private fun shouldWipeNow(expectedSha256: ByteArray): Boolean {
        // Android 10+ returns null to an app that is not focused, so null cannot prove
        // that the clipboard no longer holds our password.
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
        val text = runCatching { clip?.getItemAt(0)?.text?.toString() }.getOrNull()
        return ClipboardWipePolicy.shouldWipe(expectedSha256, clip != null, text)
    }

    private fun wipeSystemClipboard() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }
}

internal object ClipboardWipePolicy {
    /**
     * [clipReadable] is false when the clipboard is empty or hidden from this app;
     * [currentText] is the text of a readable clip, or null for non-text content.
     */
    fun shouldWipe(expectedSha256: ByteArray, clipReadable: Boolean, currentText: String?): Boolean {
        if (!clipReadable) return true
        if (currentText == null) return false
        val bytes = currentText.toByteArray(Charsets.UTF_8)
        val currentHash = Spv2Primitives.sha256(bytes)
        Spv2Spec.zeroize(bytes)
        val matches = Spv2Primitives.constantTimeEquals(currentHash, expectedSha256)
        Spv2Spec.zeroize(currentHash)
        return matches
    }
}
