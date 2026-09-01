package com.ztune.libretune.ui.screens

import androidx.compose.runtime.Composable
import com.ztune.libretune.ui.screens.datalog.DatalogScreen

/**
 * Delegates to the full implementation in the datalog sub-package.
 */
@Composable
fun DatalogScreen(
    onNavigateToConnection: () -> Unit = {}
) {
    com.ztune.libretune.ui.screens.datalog.DatalogScreen(
        onNavigateToConnection = onNavigateToConnection
    )
}
