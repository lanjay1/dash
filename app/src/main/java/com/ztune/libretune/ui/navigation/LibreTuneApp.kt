package com.ztune.libretune.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ztune.libretune.R
import com.ztune.libretune.ui.screens.HomeScreen
import com.ztune.libretune.ui.screens.analysis.VeAnalysisScreen
import com.ztune.libretune.ui.screens.autotune.AutoTuneScreen
import com.ztune.libretune.ui.screens.calibration.SensorCalibrationScreen
import com.ztune.libretune.ui.screens.connection.ConnectionScreen
import com.ztune.libretune.ui.screens.curve_editor.CurveEditorScreen
import com.ztune.libretune.ui.screens.dashboard.DashboardScreen
import com.ztune.libretune.ui.screens.datalog.DatalogChartScreen
import com.ztune.libretune.ui.screens.datalog.DatalogScreen
import com.ztune.libretune.ui.screens.diagnostics.EcuConsoleScreen
import com.ztune.libretune.ui.screens.diagnostics.ToothLoggerScreen
import com.ztune.libretune.ui.screens.git.TuneHistoryScreen
import com.ztune.libretune.ui.screens.help.HelpViewerScreen
import com.ztune.libretune.ui.screens.lua.LuaConsoleScreen
import com.ztune.libretune.ui.screens.settings.SettingsScreen
import com.ztune.libretune.ui.screens.TuneEditorScreen
import com.ztune.libretune.ui.screens.tune_editor.TableEditorScreen
import com.ztune.libretune.ui.screens.engine_constants.EngineConstantsScreen
import com.ztune.libretune.ui.screens.performance.PerformanceCalculatorScreen
import com.ztune.libretune.ui.screens.ai_assistant.AiAssistantScreen
import com.ztune.libretune.ui.screens.scripting.ActionScriptingScreen
import com.ztune.libretune.ui.screens.plugin.PluginManagerScreen
import com.ztune.libretune.ui.screens.importexport.ImportExportScreen
import com.ztune.libretune.ui.screens.ecu_definition.EcuDefinitionBrowserScreen
import com.ztune.libretune.ui.screens.pinconfig.PinConfigScreen
import com.ztune.libretune.ui.screens.project.ProjectHomeScreen
import com.ztune.libretune.ui.screens.search.GlobalSearchScreen
import com.ztune.libretune.ui.screens.help.HelpAboutDiagnosticsScreen
import kotlinx.coroutines.launch

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
    const val DATALOG_CHART = "datalog_chart/{filePath}"
    const val CALIBRATION = "calibration"
    const val LUA_CONSOLE = "lua_console"
    const val TUNE_HISTORY = "tune_history"
    const val VE_ANALYSIS = "ve_analysis"
    const val TOOTH_LOGGER = "tooth_logger"
    const val ECU_CONSOLE = "ecu_console"
    const val HELP = "help/{topic}"
    const val SETTINGS = "settings"
    const val CONNECTION = "connection"
    // New routes — UI-first completion
    const val ENGINE_CONSTANTS = "engine_constants"
    const val PERFORMANCE_CALC = "performance_calculator"
    const val AI_ASSISTANT = "ai_assistant"
    const val ACTION_SCRIPTING = "action_scripting"
    const val PLUGIN_MANAGER = "plugin_manager"
    const val IMPORT_EXPORT = "import_export"
    const val ECU_DEFINITION_BROWSER = "ecu_definition_browser"
    const val PIN_CONFIG = "pin_config"
    const val PROJECT_HOME = "project_home"
    const val GLOBAL_SEARCH = "global_search"
    const val HELP_ABOUT = "help_about"
}

/**
 * Root composable for ZTune navigation.
 *
 * Phase 30: All 11 placeholder routes are now wired to real screen
 * implementations. MenuTreeViewModel is @HiltViewModel and observes
 * EcuConnectionManager.activeDefinition reactively — the drawer/topbar/
 * bottombar now render correctly when an ECU is connected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibreTuneApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Phase 30: MenuTreeViewModel is now @HiltViewModel — observes
    // EcuConnectionManager.activeDefinition reactively.
    val menuTreeVm: MenuTreeViewModel = hiltViewModel()
    val menuTree by menuTreeVm.menuTree.collectAsState()
    val hasDefinition = menuTree.isNotEmpty()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Routes that should hide the bottom bar and drawer.
    val hideChromeRoutes = setOf(
        Routes.HOME, Routes.CONNECTION,
        Routes.TABLE_EDITOR, Routes.CURVE_EDITOR, Routes.DIALOG, Routes.HELP
    )
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
                            // route di sini adalah targetName dari MenuItemUi,
                            // bisa berupa tableName, dialogName, atau command.
                            // Kita perlu translate ke navigation route yang benar.
                            val navRoute = resolveMenuRoute(route, menuTree)
                            if (navRoute != null) {
                                navController.navigate(navRoute)
                            }
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
                // -- Home ---------------------------------------------------------------
                composable(Routes.HOME) {
                    HomeScreen(
                        onNavigateToDashboard = { navController.navigateToTopLevel(Routes.DASHBOARD) },
                        onNavigateToTuneEditor = { navController.navigateToTopLevel(Routes.TUNE_EDITOR) },
                        onNavigateToConnection = { navController.navigate(Routes.CONNECTION) }
                    )
                }

                // -- Connection ---------------------------------------------------------
                composable(Routes.CONNECTION) {
                    ConnectionScreen(
                        onNavigateToDashboard = { navController.navigateToTopLevel(Routes.DASHBOARD) }
                    )
                }

                // -- Dashboard ----------------------------------------------------------
                composable(Routes.DASHBOARD) {
                    DashboardScreen(
                        onNavigateToConnection = { navController.navigate(Routes.CONNECTION) }
                    )
                }

                // -- Tune editor overview ----------------------------------------------
                composable(Routes.TUNE_EDITOR) {
                    TuneEditorScreen(
                        onNavigateToConnection = { navController.navigate(Routes.CONNECTION) },
                        onNavigateToTable = { tableName ->
                            navController.navigate("table_editor/$tableName")
                        }
                    )
                }

                // -- Table editor -------------------------------------------------------
                composable(
                    route = Routes.TABLE_EDITOR,
                    arguments = listOf(navArgument("name") { type = NavType.StringType })
                ) { backStack ->
                    val name = backStack.arguments?.getString("name").orEmpty()
                    TableEditorScreen(tableName = name)
                }

                // -- Curve editor -------------------------------------------------------
                composable(
                    route = Routes.CURVE_EDITOR,
                    arguments = listOf(navArgument("name") { type = NavType.StringType })
                ) { backStack ->
                    val name = backStack.arguments?.getString("name").orEmpty()
                    CurveEditorScreen(
                        onBack = { navController.popBackStack() },
                        onSave = { navController.popBackStack() }
                    )
                }

                // -- Dialog (constant editor) ------------------------------------------
                composable(
                    route = Routes.DIALOG,
                    arguments = listOf(navArgument("name") { type = NavType.StringType })
                ) { backStack ->
                    val name = backStack.arguments?.getString("name").orEmpty()
                    // Dialog editor renders inline settings for a named dialog
                    // from the INI definition. For now, delegate to the table
                    // editor which can handle scalar constants.
                    TableEditorScreen(tableName = name)
                }

                // -- Auto-tune ----------------------------------------------------------
                composable(Routes.AUTOTUNE) {
                    AutoTuneScreen()
                }

                // -- Datalog ------------------------------------------------------------
                composable(Routes.DATALOG) {
                    DatalogScreen(
                        onNavigateToConnection = { navController.navigate(Routes.CONNECTION) }
                    )
                }

                // -- Datalog chart viewer ----------------------------------------------
                composable(
                    route = Routes.DATALOG_CHART,
                    arguments = listOf(navArgument("filePath") { type = NavType.StringType })
                ) { backStack ->
                    DatalogChartScreen(
                        onBack = { navController.popBackStack() }
                    )
                }

                // -- Calibration --------------------------------------------------------
                composable(Routes.CALIBRATION) {
                    SensorCalibrationScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                // -- Lua console --------------------------------------------------------
                composable(Routes.LUA_CONSOLE) {
                    LuaConsoleScreen(
                        onBack = { navController.popBackStack() }
                    )
                }

                // -- Tune history (version control) ------------------------------------
                composable(Routes.TUNE_HISTORY) {
                    TuneHistoryScreen()
                }

                // -- VE analysis --------------------------------------------------------
                composable(Routes.VE_ANALYSIS) {
                    VeAnalysisScreen()
                }

                // -- Tooth logger -------------------------------------------------------
                composable(Routes.TOOTH_LOGGER) {
                    ToothLoggerScreen()
                }

                // -- ECU console --------------------------------------------------------
                composable(Routes.ECU_CONSOLE) {
                    EcuConsoleScreen()
                }

                // -- Help ---------------------------------------------------------------
                composable(
                    route = Routes.HELP,
                    arguments = listOf(navArgument("topic") { type = NavType.StringType })
                ) { backStack ->
                    val topic = backStack.arguments?.getString("topic").orEmpty()
                    HelpViewerScreen(
                        topic = com.ztune.libretune.core.ini.types.HelpTopic(title = topic, text = topic),
                        onBack = { navController.popBackStack() }
                    )
                }

                // -- Settings -----------------------------------------------------------
                composable(Routes.SETTINGS) {
                    SettingsScreen()
                }

                // -- Engine Constants --------------------------------------------------
                composable(Routes.ENGINE_CONSTANTS) {
                    EngineConstantsScreen(onNavigateBack = { navController.popBackStack() })
                }

                // -- Performance Calculator ---------------------------------------------
                composable(Routes.PERFORMANCE_CALC) {
                    PerformanceCalculatorScreen(onNavigateBack = { navController.popBackStack() })
                }

                // -- AI Assistant -------------------------------------------------------
                composable(Routes.AI_ASSISTANT) {
                    AiAssistantScreen(onNavigateBack = { navController.popBackStack() })
                }

                // -- Action Scripting ---------------------------------------------------
                composable(Routes.ACTION_SCRIPTING) {
                    ActionScriptingScreen(onNavigateBack = { navController.popBackStack() })
                }

                // -- Plugin Manager -----------------------------------------------------
                composable(Routes.PLUGIN_MANAGER) {
                    PluginManagerScreen(onNavigateBack = { navController.popBackStack() })
                }

                // -- Import / Export ----------------------------------------------------
                composable(Routes.IMPORT_EXPORT) {
                    ImportExportScreen(onNavigateBack = { navController.popBackStack() })
                }

                // -- ECU Definition Browser ---------------------------------------------
                composable(Routes.ECU_DEFINITION_BROWSER) {
                    EcuDefinitionBrowserScreen(onNavigateBack = { navController.popBackStack() })
                }

                // -- Pin Configuration --------------------------------------------------
                composable(Routes.PIN_CONFIG) {
                    PinConfigScreen(onNavigateBack = { navController.popBackStack() })
                }

                // -- Project Home -------------------------------------------------------
                composable(Routes.PROJECT_HOME) {
                    ProjectHomeScreen(onNavigateBack = { navController.popBackStack() })
                }

                // -- Global Search ------------------------------------------------------
                composable(Routes.GLOBAL_SEARCH) {
                    GlobalSearchScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onNavigate = { route -> navController.navigate(route) }
                    )
                }

                // -- Help / About / Diagnostics -----------------------------------------
                composable(Routes.HELP_ABOUT) {
                    HelpAboutDiagnosticsScreen(onNavigateBack = { navController.popBackStack() })
                }
            }
        }
    }
}

/** Navigate to a top-level destination with proper back-stack behaviour. */
private fun NavHostController.navigateToTopLevel(route: String) {
    val startDestId = graph.startDestinationId
    val options = androidx.navigation.NavOptions.Builder()
        .setPopUpTo(startDestId, inclusive = false, saveState = true)
        .setLaunchSingleTop(true)
        .setRestoreState(true)
        .build()
    navigate(route, options)
}

/**
 * Translate a menu item's [targetName] to a navigation route.
 *
 * Menu items from the INI definition have a `targetName` that is either a
 * table name, dialog name, curve name, or command. The navigation graph
 * uses parameterized routes like `table_editor/{name}` and `dialog/{name}`.
 * This function looks up the menu item by targetName, determines its type,
 * and returns the correct route string.
 *
 * Returns null if the targetName doesn't match any known menu item type.
 */
private fun resolveMenuRoute(
    targetName: String,
    menuTree: List<MenuItemUi>
): String? {
    // Find the menu item by targetName
    val item = findMenuItemByTarget(menuTree, targetName) ?: return null

    return when (item.type) {
        MenuNodeType.TABLE -> "table_editor/${encodeRoute(item.targetName)}"
        MenuNodeType.DIALOG -> Routes.ENGINE_CONSTANTS
        MenuNodeType.CURVE -> "curve_editor/${encodeRoute(item.targetName)}"
        MenuNodeType.CALIBRATION -> Routes.CALIBRATION
        MenuNodeType.DASHBOARD -> Routes.DASHBOARD
        MenuNodeType.LOG -> Routes.DATALOG
        MenuNodeType.HELP -> "help/${encodeRoute(item.targetName)}"
        MenuNodeType.INDICATOR, MenuNodeType.READOUT -> Routes.ENGINE_CONSTANTS
        MenuNodeType.COMMAND -> null
        MenuNodeType.PORT_EDITOR -> Routes.PIN_CONFIG
        MenuNodeType.FOLDER -> null
    }
}

/** Recursively search menu tree for an item with matching targetName. */
private fun findMenuItemByTarget(
    items: List<MenuItemUi>,
    targetName: String
): MenuItemUi? {
    for (item in items) {
        if (item.targetName == targetName) return item
        val found = findMenuItemByTarget(item.children, targetName)
        if (found != null) return found
    }
    return null
}

/**
 * Encode a string for use in a navigation route URL.
 * Replaces `/` and other special characters that would break route parsing.
 */
private fun encodeRoute(s: String): String =
    android.net.Uri.encode(s)

@Composable
private fun MenuTreeDrawer(
    menuItems: List<MenuItemUi>,
    onNavigate: (String) -> Unit,
    currentRoute: String?
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
                        icon = { Icon(child.icon, contentDescription = null, modifier = Modifier.padding(start = 12.dp) ) },
                        onClick = { onNavigate(child.targetName) },
                        modifier = Modifier.padding(start = 16.dp).padding(horizontal = 8.dp)
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text("Tools", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleSmall)
        NavigationDrawerItem(label = { Text("Engine Constants") }, selected = currentRoute == Routes.ENGINE_CONSTANTS,
            icon = { Icon(Icons.Outlined.Settings, null) }, onClick = { onNavigate(Routes.ENGINE_CONSTANTS) },
            modifier = Modifier.padding(horizontal = 8.dp))
        NavigationDrawerItem(label = { Text("AutoTune") }, selected = currentRoute == Routes.AUTOTUNE,
            icon = { Icon(Icons.Outlined.Tune, null) }, onClick = { onNavigate(Routes.AUTOTUNE) },
            modifier = Modifier.padding(horizontal = 8.dp))
        NavigationDrawerItem(label = { Text("Performance Calc") }, selected = currentRoute == Routes.PERFORMANCE_CALC,
            icon = { Icon(Icons.Outlined.Speed, null) }, onClick = { onNavigate(Routes.PERFORMANCE_CALC) },
            modifier = Modifier.padding(horizontal = 8.dp))
        NavigationDrawerItem(label = { Text("AI Assistant") }, selected = currentRoute == Routes.AI_ASSISTANT,
            icon = { Icon(Icons.Outlined.Lightbulb, null) }, onClick = { onNavigate(Routes.AI_ASSISTANT) },
            modifier = Modifier.padding(horizontal = 8.dp))
        NavigationDrawerItem(label = { Text("Action Scripting") }, selected = currentRoute == Routes.ACTION_SCRIPTING,
            icon = { Icon(Icons.Outlined.Terminal, null) }, onClick = { onNavigate(Routes.ACTION_SCRIPTING) },
            modifier = Modifier.padding(horizontal = 8.dp))
        NavigationDrawerItem(label = { Text("Pin Config") }, selected = currentRoute == Routes.PIN_CONFIG,
            icon = { Icon(Icons.Outlined.DeveloperBoard, null) }, onClick = { onNavigate(Routes.PIN_CONFIG) },
            modifier = Modifier.padding(horizontal = 8.dp))
        NavigationDrawerItem(label = { Text("Import/Export") }, selected = currentRoute == Routes.IMPORT_EXPORT,
            icon = { Icon(Icons.Outlined.Folder, null) }, onClick = { onNavigate(Routes.IMPORT_EXPORT) },
            modifier = Modifier.padding(horizontal = 8.dp))
        NavigationDrawerItem(label = { Text("Plugins") }, selected = currentRoute == Routes.PLUGIN_MANAGER,
            icon = { Icon(Icons.Outlined.GridView, null) }, onClick = { onNavigate(Routes.PLUGIN_MANAGER) },
            modifier = Modifier.padding(horizontal = 8.dp))
        NavigationDrawerItem(label = { Text("Projects") }, selected = currentRoute == Routes.PROJECT_HOME,
            icon = { Icon(Icons.Outlined.Folder, null) }, onClick = { onNavigate(Routes.PROJECT_HOME) },
            modifier = Modifier.padding(horizontal = 8.dp))
        NavigationDrawerItem(label = { Text("ECU Definitions") }, selected = currentRoute == Routes.ECU_DEFINITION_BROWSER,
            icon = { Icon(Icons.Outlined.Dashboard, null) }, onClick = { onNavigate(Routes.ECU_DEFINITION_BROWSER) },
            modifier = Modifier.padding(horizontal = 8.dp))
        NavigationDrawerItem(label = { Text("Search") }, selected = currentRoute == Routes.GLOBAL_SEARCH,
            icon = { Icon(Icons.AutoMirrored.Outlined.Help, null) }, onClick = { onNavigate(Routes.GLOBAL_SEARCH) },
            modifier = Modifier.padding(horizontal = 8.dp))
        NavigationDrawerItem(label = { Text("Help & About") }, selected = currentRoute == Routes.HELP_ABOUT,
            icon = { Icon(Icons.AutoMirrored.Outlined.Help, null) }, onClick = { onNavigate(Routes.HELP_ABOUT) },
            modifier = Modifier.padding(horizontal = 8.dp))
    }
}
