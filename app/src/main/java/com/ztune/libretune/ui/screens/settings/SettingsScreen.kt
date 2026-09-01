@file:Suppress("unused")

package com.ztune.libretune.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ztune.libretune.R
import com.ztune.libretune.core.ThemeMode
import com.ztune.libretune.ui.screens.connection.BAUD_RATES

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Auto-dismiss banners after 3 seconds.
    LaunchedEffect(uiState.exportSuccess, uiState.exportError) {
        if (uiState.exportSuccess != null || uiState.exportError != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.dismissMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // -------- Banner messages --------
            AnimatedVisibility(visible = uiState.exportSuccess != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                ) {
                    Text(
                        text = uiState.exportSuccess ?: "",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            AnimatedVisibility(visible = uiState.exportError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(
                        text = uiState.exportError ?: "",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // ======== Appearance ========
            SectionHeader(title = stringResource(R.string.settings_appearance))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ThemeSelector(
                        currentMode = uiState.themeMode,
                        onThemeSelected = { viewModel.setThemeMode(it) }
                    )
                }
            }

            // ======== USB Connection ========
            SectionHeader(title = stringResource(R.string.settings_usb_section))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_usb_baud),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BaudRateDropdown(
                        selectedBaud = uiState.baudRate,
                        onBaudSelected = { viewModel.setBaudRate(it) }
                    )
                }
            }

            // ======== Auto-reconnect ========
            SectionHeader(title = stringResource(R.string.settings_reconnect_section))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_auto_reconnect),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        Switch(
                            checked = uiState.autoReconnect,
                            onCheckedChange = { viewModel.setAutoReconnect(it) }
                        )
                    }

                    HorizontalDivider()

                    // Max attempts
                    Text(
                        text = stringResource(R.string.settings_max_attempts),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${uiState.reconnectMaxAttempts}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace
                    )
                    Slider(
                        value = uiState.reconnectMaxAttempts.toFloat(),
                        onValueChange = { viewModel.setReconnectMaxAttempts(it.toInt()) },
                        valueRange = 1f..20f,
                        steps = 18,
                        enabled = uiState.autoReconnect
                    )

                    HorizontalDivider()

                    // Delay
                    Text(
                        text = stringResource(R.string.settings_delay),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.settings_delay_fmt, uiState.reconnectDelayMs.toInt()),
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace
                    )
                    Slider(
                        value = uiState.reconnectDelayMs.toFloat(),
                        onValueChange = { viewModel.setReconnectDelayMs(it.toLong()) },
                        valueRange = 500f..30_000f,
                        steps = 58, // (30000-500)/500 = 59, steps = 59-1 = 58
                        enabled = uiState.autoReconnect
                    )
                }
            }

            // ======== Data export/import ========
            SectionHeader(title = "Data Management")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Export Settings",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Copy current settings to clipboard as JSON.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { viewModel.exportSettings() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export to Clipboard")
                    }
                }
            }

            // ======== About ========
            SectionHeader(title = "About")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "LibreTune",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Open-source ECU tuning for Megasquirt, Speeduino, rusEFI, and more.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(4.dp))
                    InfoRow(label = "Version", value = uiState.appVersionName)
                    InfoRow(label = "Build", value = "${uiState.appVersionCode}")
                }
            }

            // Bottom spacing
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ========================================================================
//  Sub-composables
// ========================================================================

@Composable
private fun SectionHeader(title: String) {
 Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ThemeSelector(
    currentMode: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit
) {
    val options = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, mode ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                onClick = { onThemeSelected(mode) },
                selected = currentMode == mode,
                icon = {}
            ) {
                Text(
                    text = when (mode) {
                        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                        ThemeMode.DARK -> stringResource(R.string.theme_dark)
                    },
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaudRateDropdown(
    selectedBaud: Int,
    onBaudSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = "${selectedBaud} baud",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            BAUD_RATES.forEach { baud ->
                DropdownMenuItem(
                    text = { Text("$baud baud") },
                    onClick = {
                        onBaudSelected(baud)
                        expanded = false
                    }
                )
            }
        }
    }
}
