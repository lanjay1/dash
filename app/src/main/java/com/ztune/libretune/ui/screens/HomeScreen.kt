package com.ztune.libretune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Usb
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.R
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.EcuConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager
) : ViewModel() {
    val connectionState: StateFlow<com.ztune.libretune.core.EcuConnectionState> = connectionManager.state

    val signature: String? get() = connectionManager.activeDefinition?.signature
    val tableCount: Int get() = connectionManager.activeDefinition?.tables?.size ?: 0
    val channelCount: Int get() = connectionManager.activeDefinition?.outputChannels?.size ?: 0
}

/**
 * Home / landing screen — shows live connection status and quick-action cards.
 *
 * Phase 30: Now observes [EcuConnectionManager.state] reactively via
 * [HomeViewModel]. Displays actual connection status, ECU signature,
 * table count, and channel count when connected.
 */
@Composable
fun HomeScreen(
    onNavigateToDashboard: () -> Unit = {},
    onNavigateToTuneEditor: () -> Unit = {},
    onNavigateToConnection: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val connState by viewModel.connectionState.collectAsState()
    val isConnected = connState.status == EcuConnectionStatus.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Connection status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = if (isConnected) CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            ) else CardDefaults.cardColors()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Usb,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (isConnected) MaterialTheme.colorScheme.tertiary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.home_connection_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (isConnected) {
                    Text(
                        text = "Status: Connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    connState.signature?.let { sig ->
                        Text(
                            text = "Signature: $sig",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Tables: ${viewModel.tableCount}  Channels: ${viewModel.channelCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.home_not_connected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onNavigateToConnection) {
                    Text(if (isConnected) "Manage Connection" else stringResource(R.string.home_action_connect))
                }
            }
        }

        // Quick action buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onNavigateToDashboard,
                enabled = isConnected
            ) {
                Text(stringResource(R.string.home_action_dashboard))
            }
            Button(
                onClick = onNavigateToTuneEditor,
                enabled = isConnected
            ) {
                Text(stringResource(R.string.home_action_tune))
            }
        }
    }
}
