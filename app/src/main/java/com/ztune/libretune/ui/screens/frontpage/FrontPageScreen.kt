package com.ztune.libretune.ui.screens.frontpage

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ztune.libretune.core.EcuConnectionStatus
import com.ztune.libretune.core.EcuConnectionState
import com.ztune.libretune.core.realtime.RealtimeChannelStore

/** A live gauge strip entry: canonical channel name + display label + unit. */
private data class GaugeStripItem(
    val channel: String,
    val label: String,
    val unit: String,
    val decimals: Int = 1,
    val warningThreshold: Double? = null
)

private val GAUGE_STRIP = listOf(
    GaugeStripItem("rpm", "RPM", "rpm", 0),
    GaugeStripItem("clt", "CLT", "°C", 1, warningThreshold = 105.0),
    GaugeStripItem("iat", "IAT", "°C", 1),
    GaugeStripItem("afr", "AFR", ":1", 2),
    GaugeStripItem("map", "MAP", "kPa", 1),
    GaugeStripItem("tps", "TPS", "%", 1),
    GaugeStripItem("batteryVoltage", "Batt", "V", 2, warningThreshold = 11.0)
)

/** Recent project entry stored in shared prefs. */
data class RecentProject(
    val name: String,
    val signature: String,
    val lastOpened: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrontPageScreen(
    connectionState: EcuConnectionState,
    channelStore: RealtimeChannelStore,
    recentProjects: List<RecentProject> = emptyList(),
    onNavigateToDashboard: () -> Unit = {},
    onNavigateToTuneEditor: () -> Unit = {},
    onNavigateToDatalog: () -> Unit = {},
    onNavigateToAutoTune: () -> Unit = {},
    onNavigateToCalibrations: () -> Unit = {},
    onOpenProject: (RecentProject) -> Unit = {}
) {
    val isLive = connectionState.status == EcuConnectionStatus.CONNECTED &&
        channelStore.isReceivingData(3000L)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        "ZTune",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    val sig = connectionState.signature ?: "No ECU"
                    Text(
                        sig,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            actions = {
                StatusDot(isLive = isLive, status = connectionState.status)
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- Connection card ----
            ConnectionCard(connectionState, isLive)

            // ---- Quick actions ----
            Text(
                "Quick Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionChip(Icons.Default.Dashboard, "Dashboard", onNavigateToDashboard)
                QuickActionChip(Icons.Default.Edit, "Tune Editor", onNavigateToTuneEditor)
                QuickActionChip(Icons.Default.Visibility, "Datalog", onNavigateToDatalog)
                QuickActionChip(Icons.Default.AutoGraph, "AutoTune", onNavigateToAutoTune)
                QuickActionChip(Icons.Default.Science, "Calibrations", onNavigateToCalibrations)
            }

            Spacer(Modifier.height(4.dp))

            // ---- Live gauges strip ----
            Text(
                "Live Readout",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            LiveGaugeStrip(channelStore)

            Spacer(Modifier.height(4.dp))

            // ---- Recent projects ----
            if (recentProjects.isNotEmpty()) {
                Text(
                    "Recent Projects",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                recentProjects.forEach { project ->
                    RecentProjectCard(project, onClick = { onOpenProject(project) })
                }
            }

            Spacer(Modifier.height(80.dp))
        }

        // ---- Bottom status bar ----
        BottomStatusBar(connectionState, isLive)
    }
}

@Composable
private fun StatusDot(isLive: Boolean, status: EcuConnectionStatus) {
    val color = when {
        isLive -> Color(0xFF4CAF50)
        status == EcuConnectionStatus.CONNECTING || status == EcuConnectionStatus.RECONNECTING ->
            Color(0xFFFFC107)
        status == EcuConnectionStatus.CONNECTED -> Color(0xFF8BC34A)
        else -> Color(0xFFF44336)
    }
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(12.dp)
    ) {
        Card(
            modifier = Modifier.size(12.dp),
            colors = CardDefaults.cardColors(containerColor = color),
            shape = MaterialTheme.shapes.extraSmall
        ) {}
    }
}

@Composable
private fun ConnectionCard(state: EcuConnectionState, isLive: Boolean) {
    val statusText = when (state.status) {
        EcuConnectionStatus.CONNECTED -> if (isLive) "Streaming live data" else "Connected — waiting for data"
        EcuConnectionStatus.CONNECTING -> "Connecting to ECU..."
        EcuConnectionStatus.RECONNECTING -> "Reconnecting (${state.reconnectAttempt}/${state.reconnectMaxAttempts})"
        EcuConnectionStatus.SYNCING -> "Syncing pages (${state.syncedPages}/${state.totalPages})"
        EcuConnectionStatus.ERROR -> "Error: ${state.lastError ?: "unknown"}"
        EcuConnectionStatus.DISCONNECTED -> "Not connected"
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(state.signature ?: "No ECU connected", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QuickActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(120.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LiveGaugeStrip(channelStore: RealtimeChannelStore) {
    // Observe channels to trigger recomposition on every update
    @Suppress("UNUSED_VARIABLE")
    val channelState = channelStore.channels.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (item in GAUGE_STRIP) {
            val value = channelStore.getChannelValue(item.channel, Double.NaN)
            val displayText = if (value.isNaN()) "—" else "${"%.${item.decimals}f".format(value)} ${item.unit}"
            val isWarning = item.warningThreshold != null && !value.isNaN() && value > item.warningThreshold!!
            val bgColor = when {
                isWarning -> MaterialTheme.colorScheme.errorContainer
                value.isNaN() -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.primaryContainer
            }
            val textColor = when {
                isWarning -> MaterialTheme.colorScheme.onErrorContainer
                value.isNaN() -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onPrimaryContainer
            }
            Card(
                modifier = Modifier.width(90.dp),
                colors = CardDefaults.cardColors(containerColor = bgColor)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(item.label, style = MaterialTheme.typography.labelSmall, color = textColor, fontWeight = FontWeight.Bold)
                    Text(
                        displayText,
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentProjectCard(project: RecentProject, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(project.signature, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun BottomStatusBar(state: EcuConnectionState, isLive: Boolean) {
    val label = when {
        isLive -> "● LIVE"
        state.status == EcuConnectionStatus.CONNECTED -> "◉ CONNECTED"
        state.status == EcuConnectionStatus.CONNECTING -> "◌ CONNECTING..."
        state.status == EcuConnectionStatus.RECONNECTING -> "◌ RECONNECTING (${state.reconnectAttempt}/${state.reconnectMaxAttempts})"
        state.status == EcuConnectionStatus.SYNCING -> "⏳ SYNCING ${state.syncedPages}/${state.totalPages}"
        state.status == EcuConnectionStatus.ERROR -> "✕ ERROR"
        else -> "○ DISCONNECTED"
    }
    val color = when {
        isLive -> Color(0xFF4CAF50)
        state.status == EcuConnectionStatus.CONNECTED -> Color(0xFF8BC34A)
        state.status == EcuConnectionStatus.CONNECTING || state.status == EcuConnectionStatus.RECONNECTING ->
            Color(0xFFFFC107)
        else -> Color(0xFFF44336)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
            state.transportName?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
