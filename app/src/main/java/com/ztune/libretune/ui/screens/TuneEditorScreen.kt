package com.ztune.libretune.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.ztune.libretune.R
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.EcuConnectionStatus
import com.ztune.libretune.core.ini.types.TableDefinition
import com.ztune.libretune.core.ini.types.TableRole
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TuneEditorOverviewViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager
) : ViewModel() {
    val connectionState get() = connectionManager.state

    val tables: List<TableDefinition>
        get() = connectionManager.activeDefinition?.tables?.values?.toList()?.sortedBy { it.title } ?: emptyList()

    val isConnected: Boolean
        get() = connectionState.value.status == EcuConnectionStatus.CONNECTED
}

/**
 * Tune editor overview — lists all available tables from the ECU definition.
 *
 * Phase 30: Replaced the previous stub with a functional table list.
 * Tapping a table navigates to the table editor for that specific table.
 */
@Composable
fun TuneEditorScreen(
    onNavigateToConnection: () -> Unit = {},
    onNavigateToTable: (String) -> Unit = {},
    viewModel: TuneEditorOverviewViewModel = hiltViewModel()
) {
    val connState by viewModel.connectionState.collectAsState()
    val tables = viewModel.tables

    if (!viewModel.isConnected) {
        // Not connected — show prompt
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.tune_title),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = stringResource(R.string.tune_not_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onNavigateToConnection) {
                Text(stringResource(R.string.tune_connect_hint))
            }
        }
        return
    }

    // Connected — show table list
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.tune_title),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "${tables.size} tables available",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (tables.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No tables in ECU definition", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "The INI definition may not have [TableEditor] sections, or the parser didn't resolve them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(tables) { table ->
                TableCard(
                    table = table,
                    onClick = { onNavigateToTable(table.name) }
                )
            }
        }
    }
}

@Composable
private fun TableCard(table: TableDefinition, onClick: () -> Unit) {
    val roleIcon = when (table.role) {
        TableRole.VE -> Icons.Default.Tune
        TableRole.IGNITION -> Icons.Default.ShowChart
        TableRole.AFR_TARGET -> Icons.Default.GridOn
        else -> Icons.Default.GridOn
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = roleIcon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = table.title.ifEmpty { table.name },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${table.rows}×${table.cols}  ${table.units}  ${table.role.name}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
