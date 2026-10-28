/*
 * StarryPass Android — Hardware-Backed Biometric Master Password Vault (Opt-In)
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 *
 * Uses Android Keystore (`AndroidKeyStore`, TEE/StrongBox backed) AES-256-GCM with
 * `setUserAuthenticationRequired(true)` and per-operation `BiometricPrompt.CryptoObject`.
 * By default, nothing is enrolled (100% zero-storage). When opted in by the user,
 * decryption is cryptographically gated by hardware biometric authentication.
 */
package io.github.lingyicute.starrypass.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.lingyicute.starrypass.R
import io.github.lingyicute.starrypass.crypto.Spv2Spec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

enum class BiometricHardwareStatus {
    AVAILABLE,
    NONE_ENROLLED,
    NO_HARDWARE
}

class BiometricMasterVault(
    private val appContext: Context,
    private val sessionWipes: SessionWipeSignal
) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "starrypass_biometric_master_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PREF_FILE = "starrypass_biometric_vault"
        private const val PREF_IV = "iv_b64"
        private const val PREF_CT = "ct_b64"
    }

    private val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    // Keep the revoke control available even if an existing key cannot be inspected or loaded.
    private val _isEnrolled = MutableStateFlow(hasStoredBlob())
    val isEnrolled: StateFlow<Boolean> = _isEnrolled.asStateFlow()

    // BiometricPrompt callbacks and this map are confined to the main thread.
    private val activePrompts = mutableMapOf<BiometricPrompt, () -> Unit>()
    private var enrollmentOwner: Any? = null

    fun cancelPendingAuthentication() {
        val pending = activePrompts.toMap()
        activePrompts.clear()
        pending.forEach { (prompt, clear) ->
            runCatching { clear() }
            runCatching { prompt.cancelAuthentication() }
        }
    }

    fun queryHardwareStatus(): BiometricHardwareStatus {
        val bm = BiometricManager.from(appContext)
        return when (bm.canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricHardwareStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricHardwareStatus.NONE_ENROLLED
            else -> BiometricHardwareStatus.NO_HARDWARE
        }
    }

    fun refreshState() {
        _isEnrolled.value = hasStoredBlob()
    }

    /**
     * Generates a fresh hardware-backed AES-256-GCM key and prompts biometric authentication
     * to encrypt the master password into the vault.
     */
    fun enrollMasterPassword(
        activity: FragmentActivity,
        masterPassword: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (masterPassword.isEmpty()) {
            onError(activity.getString(R.string.biometric_err_empty_master))
            return
        }
        if (hasStoredBlob()) {
            onError(activity.getString(R.string.biometric_err_existing_vault))
            return
        }
        if (enrollmentOwner != null) {
            onError(activity.getString(R.string.biometric_err_auth_in_progress))
            return
        }

        val owner = Any()
        enrollmentOwner = owner
        val generation = sessionWipes.generation.value
        var pendingMaster: String? = masterPassword
        var prompt: BiometricPrompt? = null
        fun finishEnrollment(discardKey: Boolean) {
            if (enrollmentOwner === owner) {
                enrollmentOwner = null
                if (discardKey) discardUnenrolledKey()
            }
        }

        try {
            val secretKey = createFreshKeystoreKey()
            if (!isHardwareBacked(secretKey)) {
                pendingMaster = null
                finishEnrollment(discardKey = true)
                onError(activity.getString(R.string.biometric_err_hardware_unverified))
                return
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.biometric_enroll_title))
                .setSubtitle(activity.getString(R.string.biometric_enroll_subtitle))
                .setNegativeButtonText(activity.getString(R.string.action_cancel))
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build()

            val executor = ContextCompat.getMainExecutor(activity)
            lateinit var authPrompt: BiometricPrompt
            authPrompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        activePrompts.remove(authPrompt)
                        if (enrollmentOwner !== owner) return
                        val secret = pendingMaster
                        pendingMaster = null
                        if (!sessionWipes.isCurrent(generation) || secret == null) {
                            finishEnrollment(discardKey = true)
                            return
                        }
                        val unlockedCipher = result.cryptoObject?.cipher
                        if (unlockedCipher == null) {
                            finishEnrollment(discardKey = true)
                            onError(activity.getString(R.string.biometric_err_cipher))
                            return
                        }
                        // Use the same NFC and malformed UTF-16 handling as derivation.
                        val plainBytes = Spv2Spec.normalizeMasterToUtf8(secret)
                        try {
                            val ct = unlockedCipher.doFinal(plainBytes)
                            val iv = unlockedCipher.iv
                            if (!sessionWipes.isCurrent(generation)) {
                                finishEnrollment(discardKey = true)
                                return
                            }
                            prefs.edit()
                                .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                                .putString(PREF_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
                                .apply()
                            _isEnrolled.value = true
                            finishEnrollment(discardKey = false)
                            onSuccess()
                        } catch (e: Exception) {
                            finishEnrollment(discardKey = true)
                            onError(e.localizedMessage ?: activity.getString(R.string.biometric_err_cipher))
                        } finally {
                            Spv2Spec.zeroize(plainBytes)
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        activePrompts.remove(authPrompt)
                        pendingMaster = null
                        if (enrollmentOwner !== owner) return
                        finishEnrollment(discardKey = true)
                        if (sessionWipes.isCurrent(generation) &&
                            errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                            errorCode != BiometricPrompt.ERROR_CANCELED
                        ) {
                            onError(errString.toString())
                        }
                    }
                }
            )
            prompt = authPrompt
            activePrompts[authPrompt] = {
                pendingMaster = null
                finishEnrollment(discardKey = true)
            }
            authPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            prompt?.let { activePrompts.remove(it) }
            pendingMaster = null
            finishEnrollment(discardKey = true)
            if (sessionWipes.isCurrent(generation)) {
                onError(e.localizedMessage ?: activity.getString(R.string.biometric_err_cipher))
            }
        }
    }

    /**
     * Prompts biometric authentication and decrypts the enrolled master password on success.
     */
    fun unlockMasterPassword(
        activity: FragmentActivity,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val ivB64 = prefs.getString(PREF_IV, null)
        val ctB64 = prefs.getString(PREF_CT, null)
        val key = getExistingKeystoreKey()
        if (ivB64.isNullOrEmpty() || ctB64.isNullOrEmpty()) {
            onError(activity.getString(R.string.biometric_err_not_enrolled))
            return
        }
        if (key == null || !isHardwareBacked(key)) {
            // Preserve the existing ciphertext and key for explicit user revocation.
            onError(activity.getString(R.string.biometric_err_hardware_unverified))
            return
        }

        val generation = sessionWipes.generation.value
        var prompt: BiometricPrompt? = null
        try {
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ct = Base64.decode(ctB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.biometric_unlock_title))
                .setSubtitle(activity.getString(R.string.biometric_unlock_subtitle))
                .setNegativeButtonText(activity.getString(R.string.action_cancel))
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build()

            val executor = ContextCompat.getMainExecutor(activity)
            lateinit var authPrompt: BiometricPrompt
            authPrompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (activePrompts.remove(authPrompt) == null || !sessionWipes.isCurrent(generation)) return
                        val unlockedCipher = result.cryptoObject?.cipher
                        if (unlockedCipher == null) {
                            onError(activity.getString(R.string.biometric_err_cipher))
                            return
                        }
                        var plainBytes: ByteArray? = null
                        try {
                            plainBytes = unlockedCipher.doFinal(ct)
                            if (sessionWipes.isCurrent(generation)) {
                                onSuccess(String(plainBytes, Charsets.UTF_8))
                            }
                        } catch (e: Exception) {
                            if (sessionWipes.isCurrent(generation)) {
                                onError(e.localizedMessage ?: activity.getString(R.string.biometric_err_cipher))
                            }
                        } finally {
                            Spv2Spec.zeroize(plainBytes)
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (activePrompts.remove(authPrompt) == null) return
                        if (sessionWipes.isCurrent(generation) &&
                            errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                            errorCode != BiometricPrompt.ERROR_CANCELED
                        ) {
                            onError(errString.toString())
                        }
                    }
                }
            )
            prompt = authPrompt
            activePrompts[authPrompt] = { }
            authPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } catch (_: KeyPermanentlyInvalidatedException) {
            prompt?.let { activePrompts.remove(it) }
            if (sessionWipes.isCurrent(generation)) {
                onError(activity.getString(R.string.biometric_err_key_invalidated))
            }
        } catch (e: Exception) {
            prompt?.let { activePrompts.remove(it) }
            if (sessionWipes.isCurrent(generation)) {
                onError(e.localizedMessage ?: activity.getString(R.string.biometric_err_cipher))
            }
        }
    }

    /**
     * Destroys the Android Keystore hardware key and wipes encrypted ciphertext.
     */
    fun clearVault() {
        cancelPendingAuthentication()
        prefs.edit().clear().apply()
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS)
            }
        }
        _isEnrolled.value = false
    }

    private fun hasStoredBlob(): Boolean =
        !prefs.getString(PREF_IV, null).isNullOrEmpty() || !prefs.getString(PREF_CT, null).isNullOrEmpty()

    private fun isHardwareBacked(key: SecretKey): Boolean = try {
        val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            KeystoreHardwarePolicy.isHardwareBacked(Build.VERSION.SDK_INT, info.securityLevel, null)
        } else {
            @Suppress("DEPRECATION")
            KeystoreHardwarePolicy.isHardwareBacked(Build.VERSION.SDK_INT, null, info.isInsideSecureHardware)
        }
    } catch (_: Exception) {
        // Failure to inspect the key is not evidence of hardware isolation.
        false
    }

    private fun discardUnenrolledKey() {
        if (hasStoredBlob()) return
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getExistingKeystoreKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getKey(KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    private fun createFreshKeystoreKey(): SecretKey {
        check(!hasStoredBlob()) { "Existing vault must be revoked explicitly" }
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            ks.deleteEntry(KEY_ALIAS)
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val specBuilder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            specBuilder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            specBuilder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }
}
