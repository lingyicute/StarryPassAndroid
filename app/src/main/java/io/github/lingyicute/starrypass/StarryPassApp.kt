/*
 * StarryPass Android — Application Container & Process Background Wipe Observer
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.lingyicute.starrypass.crypto.Spv2Engine
import io.github.lingyicute.starrypass.data.PreferencesRepository
import io.github.lingyicute.starrypass.security.BiometricMasterVault
import io.github.lingyicute.starrypass.security.SecureClipboardManager
import io.github.lingyicute.starrypass.security.SessionWipeSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StarryPassApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var engine: Spv2Engine
        private set

    lateinit var preferencesRepository: PreferencesRepository
        private set

    lateinit var biometricVault: BiometricMasterVault
        private set

    lateinit var clipboardManager: SecureClipboardManager
        private set

    val sessionWipes = SessionWipeSignal()

    @Volatile
    private var cachedWipeOnBackground: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        engine = Spv2Engine()
        preferencesRepository = PreferencesRepository(this)
        biometricVault = BiometricMasterVault(this, sessionWipes)
        clipboardManager = SecureClipboardManager(this)

        // Run FIPS-style KAT self-test proactively on background thread at startup
        appScope.launch {
            runCatching { engine.runSelfTest() }
        }

        appScope.launch {
            preferencesRepository.settingsFlow.collect { settings ->
                cachedWipeOnBackground = settings.wipeOnBackground
            }
        }

        // Observe whole-app background transitions (does NOT fire on screen rotation!)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    val knownSetting = cachedWipeOnBackground
                    if (knownSetting == true) {
                        sessionWipes.invalidate()
                        biometricVault.cancelPendingAuthentication()
                        appScope.launch {
                            engine.wipeSession()
                        }
                    } else if (knownSetting == null) {
                        appScope.launch {
                            val settings = preferencesRepository.settingsFlow.first()
                            cachedWipeOnBackground = settings.wipeOnBackground
                            if (settings.wipeOnBackground) {
                                // Invalidate UI and biometric callbacks before waiting for a possibly
                                // non-interruptible Argon2 operation holding the engine mutex.
                                val shouldWipe = withContext(Dispatchers.Main.immediate) {
                                    if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                        sessionWipes.invalidate()
                                        biometricVault.cancelPendingAuthentication()
                                        true
                                    } else {
                                        false
                                    }
                                }
                                if (shouldWipe) {
                                    engine.wipeSession()
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}
