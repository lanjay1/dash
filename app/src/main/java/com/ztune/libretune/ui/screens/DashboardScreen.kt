@file:Suppress("unused")

package com.ztune.libretune.ui.screens

import androidx.compose.runtime.Composable
import com.ztune.libretune.ui.screens.dashboard.DashboardScreen as NewDashboardScreen

/**
 * Real-time dashboard — delegates to the full implementation in the `dashboard` sub-package.
 */
@Composable
fun DashboardScreen(
    onNavigateToConnection: () -> Unit = {}
) {
    NewDashboardScreen(
        onNavigateToConnection = onNavigateToConnection
    )
}
