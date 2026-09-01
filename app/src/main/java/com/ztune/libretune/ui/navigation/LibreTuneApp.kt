package com.ztune.libretune.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ztune.libretune.ui.screens.ConnectionScreen
import com.ztune.libretune.ui.screens.DashboardScreen
import com.ztune.libretune.ui.screens.DatalogScreen
import com.ztune.libretune.ui.screens.HomeScreen
import com.ztune.libretune.ui.screens.SettingsScreen
import com.ztune.libretune.ui.screens.TuneEditorScreen

private const val CONNECTION_ROUTE = "connection"

/**
 * Root composable: Scaffold with bottom navigation + NavHost.
 */
@Composable
fun LibreTuneApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Show the bottom bar on top-level destinations only (not on the connection screen).
    val showBottomBar = currentRoute != CONNECTION_ROUTE

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(destination.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(TopLevelDestination.HOME.route) {
                HomeScreen(
                    onNavigateToDashboard = { navController.navigateToTopLevel(TopLevelDestination.DASHBOARD.route) },
                    onNavigateToTuneEditor = { navController.navigateToTopLevel(TopLevelDestination.TUNE_EDITOR.route) },
                    onNavigateToConnection = { navController.navigate(CONNECTION_ROUTE) }
                )
            }
            composable(CONNECTION_ROUTE) {
                ConnectionScreen(
                    onNavigateToDashboard = {
                        navController.navigateToTopLevel(TopLevelDestination.DASHBOARD.route)
                    }
                )
            }
            composable(TopLevelDestination.DASHBOARD.route) {
                DashboardScreen(
                    onNavigateToConnection = { navController.navigate(CONNECTION_ROUTE) }
                )
            }
            composable(TopLevelDestination.TUNE_EDITOR.route) {
                TuneEditorScreen(
                    onNavigateToConnection = { navController.navigate(CONNECTION_ROUTE) }
                )
            }
            composable(TopLevelDestination.DATALOG.route) {
                DatalogScreen(
                    onNavigateToConnection = { navController.navigate(CONNECTION_ROUTE) }
                )
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen()
            }
        }
    }
}

/** Navigate to a top-level destination with proper back-stack behaviour. */
private fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
