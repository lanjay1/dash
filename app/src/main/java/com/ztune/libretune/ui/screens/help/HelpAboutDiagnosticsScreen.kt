package com.ztune.libretune.ui.screens.help

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.realtime.RealtimeChannelStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager,
    private val channelStore: RealtimeChannelStore
) : ViewModel() {
    val connectionState get() = connectionManager.state
    val activeDefinition get() = connectionManager.activeDefinition
    val channelCount get() = channelStore.channels.value.size
    val tableCount get() = connectionManager.activeDefinition?.tables?.size ?: 0
    val constantCount get() = connectionManager.activeDefinition?.constants?.size ?: 0
    val signature get() = connectionManager.activeDefinition?.signature ?: "N/A"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpAboutDiagnosticsScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: DiagnosticsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("About", "Help", "Diagnostics")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Help & About") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> AboutTab()
                1 -> HelpTab()
                2 -> DiagnosticsTab(viewModel)
            }
        }
    }
}

@Composable
private fun AboutTab() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ZTUNE", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Android ECU Tuning Platform", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        InfoRow("Version", "0.1.0")
        InfoRow("Build", "dev")
        InfoRow("License", "GPL-2.0")
        InfoRow("Repository", "github.com/lanjay1/dash")
        InfoRow("Reference", "github.com/RallyPat/LibreTune")
        HorizontalDivider()
        Text("Supported ECUs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SupportedEcu("MegaSquirt", "MS1/MS2/MS3 — RAW serial protocol")
        SupportedEcu("Speeduino", "MS-compatible, firmware >= 0.4.x")
        SupportedEcu("rusEFI", "TS-BP protocol (CRC-16 fix pending)")
        SupportedEcu("FOME", "rusEFI fork, TS-BP protocol")
        SupportedEcu("epicEFI", "JSON over newline protocol")
    }
}

@Composable
private fun HelpTab() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HelpSection("Getting Started", listOf(
            "1. Connect your ECU via USB-OTG or use Demo mode",
            "2. The app will identify the ECU and load its INI definition",
            "3. Navigate to Dashboard to see real-time data",
            "4. Use Tune Editor to edit fuel/ignition tables",
            "5. Use AutoTune for automated VE correction"
        ))
        HelpSection("Table Editor", listOf(
            "• Tap a cell to select it",
            "• Long-press for context menu (copy, paste, interpolate)",
            "• Use toolbar buttons for Set Equal, Scale, Smooth",
            "• Undo/Redo buttons in the top bar",
            "• Burn button writes changes to ECU flash"
        ))
        HelpSection("AutoTune", listOf(
            "• Start AutoTune while driving in steady-state conditions",
            "• The engine must be at operating temperature (CLT > 60°C)",
            "• Avoid rapid throttle changes (transients are filtered)",
            "• Review recommendations before applying",
            "• Always create a restore point before applying"
        ))
        HelpSection("Safety", listOf(
            "• All writes are verified before burn",
            "• Backup is automatically created before write",
            "• If verify fails, original data is restored",
            "• AutoTune NEVER burns automatically",
            "• Pin conflicts are checked before burn"
        ))
    }
}

@Composable
private fun DiagnosticsTab(vm: DiagnosticsViewModel) {
    val connState by vm.connectionState.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        DiagRow("Status", connState.status.name)
        DiagRow("Transport", connState.transportName ?: "N/A")
        DiagRow("Signature", vm.signature)
        DiagRow("Reconnect attempts", "${connState.reconnectAttempt}/${connState.reconnectMaxAttempts}")
        DiagRow("Sync progress", "${connState.syncedPages}/${connState.totalPages} pages")
        connState.lastError?.let { DiagRow("Last error", it) }

        HorizontalDivider()
        Text("Definition", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        DiagRow("Tables", vm.tableCount.toString())
        DiagRow("Constants", vm.constantCount.toString())
        DiagRow("Channels", vm.channelCount.toString())

        HorizontalDivider()
        Text("Realtime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        DiagRow("Receiving data", if (vm.channelCount > 0) "Yes" else "No")
        DiagRow("Channel count", vm.channelCount.toString())
    }
}

@Composable private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
@Composable private fun SupportedEcu(name: String, desc: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Verified, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column { Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable private fun HelpSection(title: String, items: List<String>) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    items.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp)) }
    Spacer(Modifier.height(8.dp))
}
@Composable private fun DiagRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
