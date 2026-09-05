package com.ztune.libretune.ui.screens.ecu_definition

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.EcuDefinitionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DefinitionUiItem(val fileName: String, val path: String, val ecuType: String, val signature: String)
data class DefinitionBrowserState(val definitions: List<DefinitionUiItem> = emptyList(),
    val searchQuery: String = "", val isLoading: Boolean = false)

@HiltViewModel
class EcuDefinitionBrowserViewModel @Inject constructor(
    private val repo: EcuDefinitionRepository
) : ViewModel() {
    private val _state = MutableStateFlow(DefinitionBrowserState())
    val state: StateFlow<DefinitionBrowserState> = _state.asStateFlow()

    init { loadDefinitions() }

    fun loadDefinitions() {
        viewModelScope.launch {
            val defs = repo.listDefinitions().map {
                DefinitionUiItem(it.fileName, it.path, it.ecuType.name, it.signature)
            }
            _state.value = DefinitionBrowserState(definitions = defs)
        }
    }

    fun onSearchChange(q: String) {
        _state.value = _state.value.copy(searchQuery = q,
            definitions = if (q.isBlank()) _state.value.definitions
                else _state.value.definitions.filter { it.fileName.contains(q, true) || it.signature.contains(q, true) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcuDefinitionBrowserScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: EcuDefinitionBrowserViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ECU Definitions") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.searchQuery, onValueChange = viewModel::onSearchChange,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Search definitions...") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true
            )
            if (state.definitions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No definitions found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.definitions) { def ->
                        Card(modifier = Modifier.fillMaxWidth(), onClick = {}) {
                            Column(Modifier.padding(16.dp)) {
                                Text(def.fileName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("Type: ${def.ecuType}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (def.signature.isNotEmpty()) {
                                    Text("Signature: ${def.signature}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
