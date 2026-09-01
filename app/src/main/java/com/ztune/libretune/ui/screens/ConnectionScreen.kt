package com.ztune.libretune.ui.screens

import androidx.compose.runtime.Composable
import com.ztune.libretune.ui.screens.connection.ConnectionScreen

/**
 * Delegates to the full implementation in the connection sub-package.
 */
@Composable
fun ConnectionScreen(
    onNavigateToDashboard: () -> Unit = {}
) {
    com.ztune.libretune.ui.screens.connection.ConnectionScreen(
        onNavigateToDashboard = onNavigateToDashboard
    )
}
