@file:Suppress("unused")

package com.ztune.libretune.ui.screens.datalog

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ztune.libretune.R
import com.ztune.libretune.core.DataLogRecordingState
import com.ztune.libretune.core.DatalogSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Datalog screen — recording controls, session list, channel selector, and playback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatalogScreen(
    onNavigateToConnection: () -> Unit = {},
    viewModel: DatalogViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show toast messages via snackbar.
    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dl_title)) },
                actions = {
                    // Channel picker button.
                    BadgedBox(
                        badge = {
                            val count = uiState.selectedChannels.size
                            if (count > 0) {
                                Badge { Text("$count") }
                            }
                        }
                    ) {
                        IconButton(onClick = { viewModel.toggleChannelPicker() }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Select channels")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- Connection status ----------
            if (!uiState.isConnected) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToConnection() }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.TrendingUp,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.dl_not_connected),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Tap to connect",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            // ---------- Recording status ----------
            item {
                RecordingStatusCard(
                    recordingState = uiState.recordingState,
                    sampleCount = uiState.sampleCount,
                    selectedChannelCount = uiState.selectedChannels.size
                )
            }

            // ---------- Recording controls ----------
            item {
                RecordingControls(
                    recordingState = uiState.recordingState,
                    isConnected = uiState.isConnected,
                    onStart = { viewModel.startRecording() },
                    onPause = { viewModel.pauseRecording() },
                    onResume = { viewModel.resumeRecording() },
                    onStop = { viewModel.stopRecording() }
                )
            }

            // ---------- Sessions header ----------
            item {
                Text(
                    text = "Recorded Sessions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // ---------- Sessions list ----------
            if (uiState.sessions.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No recorded sessions yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(uiState.sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onDelete = { viewModel.requestDeleteSession(session.id) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }

        // ---------- Channel picker dialog ----------
        if (uiState.showChannelPicker) {
            ChannelPickerDialog(
                availableChannels = uiState.availableChannels,
                selectedChannels = uiState.selectedChannels,
                onToggle = { viewModel.toggleChannel(it) },
                onSelectAll = { viewModel.selectAllChannels() },
                onDeselectAll = { viewModel.deselectAllChannels() },
                onDismiss = { viewModel.toggleChannelPicker() }
            )
        }

        // ---------- Delete confirmation dialog ----------
        uiState.deleteConfirmId?.let { sessionId ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissDeleteConfirm() },
                title = { Text("Delete Session") },
                text = { Text("This recorded session will be permanently deleted. Continue?") },
                confirmButton = {
                    TextButton(onClick = { viewModel.deleteSession(sessionId) }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissDeleteConfirm() }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

// ========================================================================
//  Sub-composables
// ========================================================================

@Composable
private fun RecordingStatusCard(
    recordingState: DataLogRecordingState,
    sampleCount: Int,
    selectedChannelCount: Int
) {
    val isRecording = recordingState == DataLogRecordingState.RECORDING
    val isPaused = recordingState == DataLogRecordingState.PAUSED

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isRecording -> MaterialTheme.colorScheme.primaryContainer
                isPaused -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pulsing dot when recording.
                if (isRecording) {
                    RecordingDot()
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = when (recordingState) {
                        DataLogRecordingState.IDLE -> "Ready to record"
                        DataLogRecordingState.RECORDING -> "Recording..."
                        DataLogRecordingState.PAUSED -> "Paused"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (isRecording || isPaused) {
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    Text(
                        text = "Samples: $sampleCount",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Channels: $selectedChannelCount",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingDot() {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(600),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        )
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                color = MaterialTheme.colorScheme.error.copy(alpha = alpha),
                shape = CircleShape
            )
    )
}

@Composable
private fun RecordingControls(
    recordingState: DataLogRecordingState,
    isConnected: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (recordingState) {
            DataLogRecordingState.IDLE -> {
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                    enabled = isConnected
                ) {
                    Icon(Icons.Default.FiberManualRecord, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dl_start))
                }
            }
            DataLogRecordingState.RECORDING -> {
                FilledTonalButton(
                    onClick = onPause,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pause")
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dl_stop))
                }
            }
            DataLogRecordingState.PAUSED -> {
                Button(
                    onClick = onResume,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Resume")
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dl_stop))
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: DatalogSession,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.TrendingUp,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateFormat.format(Date(session.startTimeMs)),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "${session.sampleCount} samples",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${session.channelNames.size} ch",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Show first few channel names.
                if (session.channelNames.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = session.channelNames.take(5).joinToString(", ") +
                                if (session.channelNames.size > 5) " ..." else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete session",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ChannelPickerDialog(
    availableChannels: List<String>,
    selectedChannels: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Channels") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onSelectAll) { Text("Select All") }
                    TextButton(onClick = onDeselectAll) { Text("Deselect All") }
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(availableChannels) { channel ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(channel) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(
                                checked = channel in selectedChannels,
                                onCheckedChange = { onToggle(channel) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = channel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
