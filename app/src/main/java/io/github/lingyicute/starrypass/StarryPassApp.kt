/*
 * StarryPass Android — Application Container & Process Background Wipe Observer
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.lingyicute.starrypass.crypto.Spv2Engine
import io.github.lingyicute.starrypass.data.PreferencesRepository
import io.github.lingyicute.starrypass.security.BiometricMasterVault
import io.github.lingyicute.starrypass.security.SecureClipboardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    private val _sessionWipeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionWipeEvents: SharedFlow<Unit> = _sessionWipeEvents.asSharedFlow()

    override fun onCreate() {
        super.onCreate()
        engine = Spv2Engine()
        preferencesRepository = PreferencesRepository(this)
        biometricVault = BiometricMasterVault(this)
        clipboardManager = SecureClipboardManager(this)

        // Run FIPS-style KAT self-test proactively on background thread at startup
        appScope.launch {
            runCatching { engine.runSelfTest() }
        }

        // Observe whole-app background transitions (does NOT fire on screen rotation!)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    appScope.launch {
                        val settings = preferencesRepository.settingsFlow.first()
                        if (settings.wipeOnBackground) {
                            engine.wipeSession()
                            _sessionWipeEvents.tryEmit(Unit)
                        }
                    }
                }
            }
        )
    }
}
