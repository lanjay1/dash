package com.ztune.libretune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ztune.libretune.R

/**
 * Home / landing screen — shows connection status and quick-action cards.
 *
 * TODO: Observe [EcuConnectionManager.state] and display live status.
 */
@Composable
fun HomeScreen(
    onNavigateToDashboard: () -> Unit = {},
    onNavigateToTuneEditor: () -> Unit = {},
    onNavigateToConnection: () -> Unit = {}
) {
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
        Card(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.home_connection_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.home_not_connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onNavigateToConnection) {
                    Text(stringResource(R.string.home_action_connect))
                }
            }
        }

        Button(onClick = onNavigateToDashboard) {
            Text(stringResource(R.string.home_action_dashboard))
        }
        Button(onClick = onNavigateToTuneEditor) {
            Text(stringResource(R.string.home_action_tune))
        }
    }
}
