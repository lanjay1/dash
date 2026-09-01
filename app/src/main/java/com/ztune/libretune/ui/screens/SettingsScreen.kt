package com.ztune.libretune.ui.screens

import androidx.compose.runtime.Composable
import com.ztune.libretune.ui.screens.settings.SettingsScreen

/**
 * Delegates to the full implementation in the settings sub-package.
 */
@Composable
fun SettingsScreen() {
    com.ztune.libretune.ui.screens.settings.SettingsScreen()
}
