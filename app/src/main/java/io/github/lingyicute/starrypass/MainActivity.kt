/*
 * StarryPass Android — Main Edge-to-Edge Activity
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import io.github.lingyicute.starrypass.ui.MainViewModel
import io.github.lingyicute.starrypass.ui.StarryPassRootScreen
import io.github.lingyicute.starrypass.ui.theme.StarryPassTheme

class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settingsState.collectAsState()
            StarryPassTheme(
                themePreference = settings.theme,
                dynamicColor = settings.dynamicColor
            ) {
                StarryPassRootScreen(
                    activity = this@MainActivity,
                    viewModel = viewModel
                )
            }
        }
    }
}
