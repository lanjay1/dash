package com.ztune.libretune.ui.screens.engine_constants

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.Constant
import com.ztune.libretune.core.ini.types.DataType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConstantUiItem(
    val name: String,
    val label: String,
    val value: Double,
    val unit: String,
    val min: Double,
    val max: Double,
    val dataType: DataType,
    val category: String,
    val isModified: Boolean = false
)

data class EngineConstantsUiState(
    val constants: List<ConstantUiItem> = emptyList(),
    val filteredConstants: List<ConstantUiItem> = emptyList(),
    val searchQuery: String = "",
    val expandedCategories: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class EngineConstantsViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager
) : ViewModel() {
    private val _state = MutableStateFlow(EngineConstantsUiState())
    val state: StateFlow<EngineConstantsUiState> = _state.asStateFlow()

    init { loadConstants() }

    fun loadConstants() {
        val def = connectionManager.activeDefinition
        if (def == null) {
            _state.value = EngineConstantsUiState(
                constants = getDemoConstants(),
                filteredConstants = getDemoConstants(),
                expandedCategories = getDemoConstants().map { it.category }.toSet()
            )
            return
        }
        val items = def.constants.map { (name, c) ->
            ConstantUiItem(
                name = name, label = name.replace("_", " "),
                value = 0.0, unit = c.units, min = c.min, max = c.max,
                dataType = c.dataType, category = categorize(name)
            )
        }
        _state.value = EngineConstantsUiState(
            constants = items, filteredConstants = items,
            expandedCategories = items.map { it.category }.toSet()
        )
    }

    fun onSearchChange(query: String) {
        val filtered = if (query.isBlank()) _state.value.constants
            else _state.value.constants.filter { it.name.contains(query, ignoreCase = true) || it.label.contains(query, ignoreCase = true) }
        _state.value = _state.value.copy(searchQuery = query, filteredConstants = filtered)
    }

    fun toggleCategory(cat: String) {
        val expanded = _state.value.expandedCategories.toMutableSet()
        if (cat in expanded) expanded.remove(cat) else expanded.add(cat)
        _state.value = _state.value.copy(expandedCategories = expanded)
    }

    fun updateValue(name: String, value: Double) {
        _state.value = _state.value.copy(
            constants = _state.value.constants.map { if (it.name == name) it.copy(value = value, isModified = true) else it },
            filteredConstants = _state.value.filteredConstants.map { if (it.name == name) it.copy(value = value, isModified = true) else it }
        )
    }

    private fun categorize(name: String): String {
        val ln = name.lowercase()
        return when {
            ln.contains("reqfuel") || ln.contains("injector") || ln.contains("inj") -> "Injection"
            ln.contains("spark") || ln.contains("ign") || ln.contains("dwell") -> "Ignition"
            ln.contains("idle") -> "Idle"
            ln.contains("boost") -> "Boost"
            ln.contains("lambda") || ln.contains("ego") || ln.contains("afr") -> "AFR/Lambda"
            ln.contains("clt") || ln.contains("iat") || ln.contains("temp") -> "Temperature"
            else -> "General"
        }
    }

    private fun getDemoConstants(): List<ConstantUiItem> = listOf(
        ConstantUiItem("reqFuel", "Required Fuel", 8.0, "ms", 0.1, 25.5, DataType.U08, "Injection"),
        ConstantUiItem("nCylinders", "Number of Cylinders", 4.0, "cyl", 1.0, 12.0, DataType.U08, "Injection"),
        ConstantUiItem("injOpen", "Injector Open Time", 1.0, "ms", 0.0, 25.5, DataType.U08, "Injection"),
        ConstantUiItem("sparkDur", "Spark Duration", 2.0, "ms", 0.1, 10.0, DataType.U08, "Ignition"),
        ConstantUiItem("dwell", "Coil Dwell", 3.5, "ms", 0.5, 8.0, DataType.U08, "Ignition"),
        ConstantUiItem("idleRpm", "Idle RPM", 850.0, "RPM", 0.0, 3000.0, DataType.U16, "Idle"),
        ConstantUiItem("egoType", "EGO Sensor Type", 0.0, "", 0.0, 7.0, DataType.BITS, "AFR/Lambda"),
        ConstantUiItem("afrStoich", "Stoichiometric AFR", 14.7, ":1", 8.0, 20.0, DataType.U08, "AFR/Lambda")
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineConstantsScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: EngineConstantsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val grouped = state.filteredConstants.groupBy { it.category }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Engine Constants") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchChange,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Search constants...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true
            )
            if (state.filteredConstants.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No constants found", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    grouped.forEach { (category, items) ->
                        item {
                            val expanded = category in state.expandedCategories
                            Card(modifier = Modifier.fillMaxWidth(),
                                onClick = { viewModel.toggleCategory(category) }) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(category, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f))
                                    Text("${items.size}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Icon(if (expanded) Icons.Default.ArrowBack else Icons.Default.ArrowBack,
                                        null, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                        if (category in state.expandedCategories) {
                            items(items) { item -> ConstantRow(item, viewModel::updateValue) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConstantRow(item: ConstantUiItem, onUpdate: (String, Double) -> Unit) {
    var editValue by remember(item.name) { mutableStateOf(item.value.toString()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (item.isModified) Text("•", color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = editValue, onValueChange = { editValue = it; it.toDoubleOrNull()?.let { v -> onUpdate(item.name, v) } },
                    modifier = Modifier.weight(1f), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.width(8.dp))
                Text(item.unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Range: ${item.min} - ${item.max} (${item.dataType})",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
