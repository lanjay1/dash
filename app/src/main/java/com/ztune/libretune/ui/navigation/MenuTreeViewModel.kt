package com.ztune.libretune.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.Menu
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class MenuNodeType {
    FOLDER,
    TABLE,
    DIALOG,
    DASHBOARD,
    LOG,
    HELP,
    INDICATOR,
    READOUT,
    COMMAND,
    CALIBRATION,
    PORT_EDITOR,
    CURVE
}

data class MenuItemUi(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val children: List<MenuItemUi> = emptyList(),
    val type: MenuNodeType = MenuNodeType.FOLDER,
    val targetName: String = "",
    val disabled: Boolean = false,
    val disabledReason: String? = null
)

class MenuTreeViewModel(definition: EcuDefinition?) : ViewModel() {

    private val _menuTree = MutableStateFlow<List<MenuItemUi>>(emptyList())
    val menuTree: StateFlow<List<MenuItemUi>> = _menuTree

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _filteredTree = MutableStateFlow<List<MenuItemUi>>(emptyList())
    val filteredTree: StateFlow<List<MenuItemUi>> = _filteredTree

    private val _searchResultCount = MutableStateFlow(0)
    val searchResultCount: StateFlow<Int> = _searchResultCount

    private val _expandedIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedIds: StateFlow<Set<String>> = _expandedIds

    private val allItems = mutableListOf<MenuItemUi>()
    private val searchableIndex = mutableMapOf<String, MenuItemUi>()

    init {
        if (definition != null) {
            buildTree(definition)
        }
        viewModelScope.launch {
            searchQuery.collect { query ->
                filterTree(query)
            }
        }
    }

    private fun autoIcon(type: MenuNodeType): ImageVector = when (type) {
        MenuNodeType.FOLDER -> Icons.Outlined.Folder
        MenuNodeType.TABLE -> Icons.Outlined.GridView
        MenuNodeType.DIALOG -> Icons.Outlined.Settings
        MenuNodeType.DASHBOARD -> Icons.Outlined.Dashboard
        MenuNodeType.LOG -> Icons.Outlined.BarChart
        MenuNodeType.HELP -> Icons.Outlined.Help
        MenuNodeType.INDICATOR -> Icons.Outlined.Lightbulb
        MenuNodeType.READOUT -> Icons.Outlined.Speed
        MenuNodeType.COMMAND -> Icons.Outlined.Terminal
        MenuNodeType.CALIBRATION -> Icons.Outlined.ShowChart
        MenuNodeType.PORT_EDITOR -> Icons.Outlined.DeveloperBoard
        MenuNodeType.CURVE -> Icons.Outlined.ShowChart
    }

    private fun buildTree(definition: EcuDefinition) {
        val items = mutableListOf<MenuItemUi>()
        var idx = 0

        fun processMenu(menu: Menu, parentPath: String): MenuItemUi {
            val path = "${parentPath}/${menu.label}".replace(" ", "_")
            val id = "menu_$idx"
            idx++

            val children = menu.subMenu.map { processMenu(it, path) }

            val nodeType = when {
                menu.tableName != null -> {
                    val tbl = definition.getTableByNameOrMap(menu.tableName!!)
                    if (tbl != null) MenuNodeType.TABLE else MenuNodeType.DIALOG
                }
                menu.dialogName != null -> {
                    if (definition.dialogs.containsKey(menu.dialogName)) MenuNodeType.DIALOG
                    else if (definition.indicatorPanels.containsKey(menu.dialogName)) MenuNodeType.INDICATOR
                    else if (definition.readoutPanels.containsKey(menu.dialogName)) MenuNodeType.READOUT
                    else if (definition.portEditors.containsKey(menu.dialogName)) MenuNodeType.PORT_EDITOR
                    else MenuNodeType.DIALOG
                }
                menu.command.startsWith("calibration", ignoreCase = true) -> MenuNodeType.CALIBRATION
                children.isNotEmpty() -> MenuNodeType.FOLDER
                menu.helpTopic != null -> MenuNodeType.HELP
                else -> MenuNodeType.COMMAND
            }

            val targetName = menu.tableName ?: menu.dialogName ?: menu.command

            val item = MenuItemUi(
                id = id,
                label = menu.label,
                icon = autoIcon(nodeType),
                children = children,
                type = nodeType,
                targetName = targetName
            )
            allItems.add(item)
            searchableIndex[item.label.lowercase()] = item

            return item
        }

        for (menu in definition.menus) {
            items.add(processMenu(menu, "root"))
        }

        _menuTree.value = items
        _filteredTree.value = items

        // Auto-expand top level
        _expandedIds.value = items.map { it.id }.toSet()
    }

    private fun filterTree(query: String) {
        if (query.isBlank()) {
            _filteredTree.value = _menuTree.value
            _searchResultCount.value = 0
            return
        }

        val q = query.lowercase()
        val matchingIds = mutableSetOf<String>()
        var directMatchCount = 0

        searchableIndex.forEach { (key, item) ->
            if (key.contains(q)) {
                matchingIds.add(item.id)
                directMatchCount++
                // Add all ancestors by adding every node in the tree
                addAncestors(matchingIds)
            }
        }

        _searchResultCount.value = directMatchCount
        _filteredTree.value = filterNodes(_menuTree.value, matchingIds)

        // Expand all matching paths
        _expandedIds.value = matchingIds
    }

    private fun addAncestors(ids: MutableSet<String>) {
        // Add every node so the tree path is preserved during search
        allItems.forEach { ids.add(it.id) }
    }

    private fun filterNodes(nodes: List<MenuItemUi>, matchIds: Set<String>): List<MenuItemUi> {
        return nodes.mapNotNull { node ->
            if (node.id in matchIds) {
                val filteredChildren = filterNodes(node.children, matchIds)
                if (filteredChildren.isEmpty() && node.children.isNotEmpty() && node.children.none { it.id in matchIds }) {
                    // Keep the node but strip children that don't match
                    node.copy(children = emptyList())
                } else {
                    node.copy(children = filteredChildren)
                }
            } else if (node.children.any { it.id in matchIds }) {
                node.copy(children = filterNodes(node.children, matchIds))
            } else {
                null
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun toggleExpanded(id: String) {
        val current = _expandedIds.value.toMutableSet()
        if (id in current) current.remove(id) else current.add(id)
        _expandedIds.value = current
    }

    fun findNodeByTarget(targetName: String): MenuItemUi? {
        return allItems.find { it.targetName == targetName }
    }
}
