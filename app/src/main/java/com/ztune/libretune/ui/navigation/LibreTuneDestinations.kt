package com.ztune.libretune.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.ztune.libretune.R

/**
 * Top-level navigation destinations.
 *
 * Single source of truth for route string, label resource, and icons.
 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val unselectedIcon: ImageVector,
    val selectedIcon: ImageVector
) {
    HOME("home", R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    DASHBOARD("dashboard", R.string.nav_dashboard, Icons.Outlined.Speed, Icons.Filled.Speed),
    TUNE_EDITOR("tune_editor", R.string.nav_tune_editor, Icons.Outlined.Tune, Icons.Filled.Tune),
    DATALOG("datalog", R.string.nav_datalog, Icons.Outlined.Analytics, Icons.Filled.Analytics),
    SETTINGS("settings", R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
    CALIBRATION("calibration", R.string.nav_calibration, Icons.Outlined.Tune, Icons.Filled.Tune),
    TOOLS("tools", R.string.nav_tools, Icons.Outlined.Build, Icons.Filled.Build);

    companion object {
        fun fromRoute(route: String?): TopLevelDestination? =
            entries.firstOrNull { it.route == route }
    }
}
