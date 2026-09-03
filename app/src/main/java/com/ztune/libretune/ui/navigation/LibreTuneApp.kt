package com.ztune.libretune.ui.navigation

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.findStartDestination
import androidx.navigation.navArgument
import com.ztune.libretune.R
import com.ztune.libretune.ui.screens.HomeScreen
import com.ztune.libretune.ui.screens.connection.ConnectionScreen
import com.ztune.libretune.ui.screens.dashboard.DashboardScreen
import com.ztune.libretune.ui.screens.datalog.DatalogScreen
import com.ztune.libretune.ui.screens.settings.SettingsScreen
import com.ztune.libretune.ui.screens.TuneEditorScreen
import com.ztune.libretune.ui.screens.tune_editor.TableEditorScreen
import kotlinx.coroutines.launch

// ======================================================================
//  Placeholder screens for routes not yet implemented
// ======================================================================

@Composable
private fun PlaceholderScreen(name: String, navController: NavHostController) {
    Box(modifier = Modifier.padding(16.dp)) {
        Text(text = name, style = MaterialTheme.typography.headlineMedium)
    }
}

// ======================================================================
//  Route constants
// ======================================================================

object Routes {
    const val HOME = "home"
    const val DASHBOARD = "dashboard"
    const val TUNE_EDITOR = "tune_editor"
    const val TABLE_EDITOR = "table_editor/{name}"
    const val CURVE_EDITOR = "curve_editor/{name}"
    const val DIALOG = "dialog/{name}"
    const val AUTOTUNE = "autotune"
    const val DATALOG = "datalog"
    const val DATALOG_CHART = "datalog_chart"
    const val CALIBRATION = "calibration"
    const val LUA_CONSOLE = "lua_console"
    const val TUNE_HISTORY = "tune_history"
    const val VE_ANALYSIS = "ve_analysis"
    const val TOOTH_LOGGER = "tooth_logger"
    const val ECU_CONSOLE = "ecu_console"
    const val HELP = "help/{topic}"
    const val SETTINGS = "settings"
    const val CONNECTION = "connection"
}

/**
 * Root composable for LibreTune navigation.
 *
 * When no ECU definition is loaded the user sees [HomeScreen].
 * Once a definition is loaded the main layout appears with a sidebar
 * (drawer on mobile) and a content area driven by [NavHost].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibreTuneApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Observe whether an ECU definition has been loaded.
    // MenuTreeViewModel is a plain ViewModel (not @HiltViewModel) because
    // EcuDefinition is a runtime-discovered value, not a Hilt-provided singleton.
    val menuTreeVm: MenuTreeViewModel = remember { MenuTreeViewModel() }
    val menuTree by menuTreeVm.menuTree.collectAsState()
    val hasDefinition = menuTree.isNotEmpty()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Routes that should hide the bottom bar and drawer.
    val hideChromeRoutes = setOf(Routes.HOME, Routes.CONNECTION, Routes.TABLE_EDITOR,
        Routes.CURVE_EDITOR, Routes.DIALOG, Routes.HELP)
    val baseRoute = currentRoute?.split("/")?.firstOrNull()
    val showChrome = baseRoute !in hideChromeRoutes

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            if (hasDefinition && showChrome) {
                ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                    MenuTreeDrawer(
                        menuItems = menuTree,
                        onNavigate = { route ->
                            scope.launch { drawerState.close() }
                            navController.navigate(route)
                        },
                        currentRoute = currentRoute
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (hasDefinition && showChrome) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.app_name)) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        }
                    )
                }
            },
            bottomBar = {
                if (hasDefinition && showChrome) {
                    val bottomItems = listOf(
                        TopLevelDestination.HOME,
                        TopLevelDestination.DASHBOARD,
                        TopLevelDestination.TUNE_EDITOR,
                        TopLevelDestination.DATALOG,
                        TopLevelDestination.SETTINGS,
                    )
                    NavigationBar {
                        bottomItems.forEach { dest ->
                            val selected = baseRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateToTopLevel(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = null
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(innerPadding)
            ) {
                // -- Home (welcome / definition loader) --------------------------------
                composable(Routes.HOME) {
                    HomeScreen(
                        onNavigateToDashboard = { navController.navigateToTopLevel(Routes.DASHBOARD) },
                        onNavigateToTuneEditor = { navController.navigateToTopLevel(Routes.TUNE_EDITOR) },
                        onNavigateToConnection = { navController.navigate(Routes.CONNECTION) }
                    )
                }

                // -- Connection ------------------------------------------------------
                composable(Routes.CONNECTION) {
                    ConnectionScreen(
                        onNavigateToDashboard = { navController.navigateToTopLevel(Routes.DASHBOARD) }
                    )
                }

                // -- Dashboard -------------------------------------------------------
                composable(Routes.DASHBOARD) {
                    DashboardScreen(
                        onNavigateToConnection = { navController.navigate(Routes.CONNECTION) }
                    )
                }

                // -- Tune editor (overview) -------------------------------------------
                composable(Routes.TUNE_EDITOR) {
                    TuneEditorScreen(
                        onNavigateToConnection = { navController.navigate(Routes.CONNECTION) }
                    )
                }

                // -- Table editor ----------------------------------------------------
                composable(
                    route = Routes.TABLE_EDITOR,
                    arguments = listOf(navArgument("name") { type = NavType.StringType })
                ) { backStack ->
                    val name = backStack.arguments?.getString("name").orEmpty()
                    TableEditorScreen(tableName = name)
                }

                // -- Curve editor ---------------------------------------------------
                composable(
                    route = Routes.CURVE_EDITOR,
                    arguments = listOf(navArgument("name") { type = NavType.StringType })
                ) { backStack ->
                    val name = backStack.arguments?.getString("name").orEmpty()
                    PlaceholderScreen("Curve Editor: $name", navController)
                }

                // -- Dialog (constant editor / single-value editor) ------------------
                composable(
                    route = Routes.DIALOG,
                    arguments = listOf(navArgument("name") { type = NavType.StringType })
                ) { backStack ->
                    val name = backStack.arguments?.getString("name").orEmpty()
                    PlaceholderScreen("Dialog: $name", navController)
                }

                // -- Auto-tune -------------------------------------------------------
                composable(Routes.AUTOTUNE) {
                    PlaceholderScreen("Auto-Tune", navController)
                }

                // -- Datalog ---------------------------------------------------------
                composable(Routes.DATALOG) {
                    DatalogScreen(
                        onNavigateToConnection = { navController.navigate(Routes.CONNECTION) }
                    )
                }

                // -- Datalog chart viewer ---------------------------------------------
                composable(Routes.DATALOG_CHART) {
                    PlaceholderScreen("Datalog Chart", navController)
                }

                // -- Calibration -----------------------------------------------------
                composable(Routes.CALIBRATION) {
                    PlaceholderScreen("Calibration", navController)
                }

                // -- Lua console -----------------------------------------------------
                composable(Routes.LUA_CONSOLE) {
                    PlaceholderScreen("Lua Console", navController)
                }

                // -- Tune history (git) ----------------------------------------------
                composable(Routes.TUNE_HISTORY) {
                    PlaceholderScreen("Tune History", navController)
                }

                // -- VE analysis -----------------------------------------------------
                composable(Routes.VE_ANALYSIS) {
                    PlaceholderScreen("VE Analysis", navController)
                }

                // -- Tooth logger ----------------------------------------------------
                composable(Routes.TOOTH_LOGGER) {
                    PlaceholderScreen("Tooth Logger", navController)
                }

                // -- ECU console -----------------------------------------------------
                composable(Routes.ECU_CONSOLE) {
                    PlaceholderScreen("ECU Console", navController)
                }

                // -- Help -----------------------------------------------------------
                composable(
                    route = Routes.HELP,
                    arguments = listOf(navArgument("topic") { type = NavType.StringType })
                ) { backStack ->
                    val topic = backStack.arguments?.getString("topic").orEmpty()
                    PlaceholderScreen("Help: $topic", navController)
                }

                // -- Settings -------------------------------------------------------
                composable(Routes.SETTINGS) {
                    SettingsScreen()
                }
            }
        }
    }
}

/** Navigate to a top-level destination with proper back-stack behaviour. */
private fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Simple drawer that renders the menu tree as a flat list of navigation entries.
 *
 * Replaces the previously-missing `MenuTree` composable with a minimal
 * implementation that shows top-level menu items and their first-level
 * children, navigating to the appropriate route on tap.
 */
@Composable
private fun MenuTreeDrawer(
    menuItems: List<MenuItemUi>,
    onNavigate: (String) -> Unit,
    currentRoute: String?
) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Menu",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium
        )
        HorizontalDivider()
        menuItems.forEach { item ->
            NavigationDrawerItem(
                label = { Text(item.label) },
                selected = currentRoute == item.targetName,
                icon = { Icon(item.icon, contentDescription = null) },
                onClick = { onNavigate(item.targetName) },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            if (item.children.isNotEmpty()) {
                item.children.take(8).forEach { child ->
                    NavigationDrawerItem(
                        label = { Text("  ${child.label}", style = MaterialTheme.typography.bodyMedium) },
                        selected = currentRoute == child.targetName,
                        icon = { Icon(child.icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = { onNavigate(child.targetName) },
                        modifier = Modifier.padding(start = 16.dp).padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}
