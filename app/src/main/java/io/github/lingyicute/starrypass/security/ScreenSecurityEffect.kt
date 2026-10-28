/*
 * StarryPass Android — Compose Window FLAG_SECURE Effect
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.security

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

@Composable
fun ScreenSecurityEffect(flagSecureEnabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(context, flagSecureEnabled) {
        val window = (context as? Activity)?.window
        if (window != null) {
            if (flagSecureEnabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        onDispose { }
    }
}
