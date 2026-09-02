package com.ztune.libretune.ui.screens.git

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.HistoryRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ztune.libretune.core.git.ChangedValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuneHistoryScreen(
    vm: TuneHistoryViewModel = viewModel(),
    onNavigateBack: () -> Unit = {},
    onTuneRestored: () -> Unit = {},
) {
    val commits by vm.commits.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val diffResult by vm.diffResult.collectAsState()
    val diffMessage by vm.diffCommitMessage.collectAsState()
    val restoredTune by vm.restoredTune.collectAsState()

    LaunchedEffect(restoredTune) {
        if (restoredTune != null) {
            vm.clearRestoredFlag()
            onTuneRestored()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tune History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (commits.isNotEmpty()) {
                        IconButton(onClick = { vm.pruneCommits() }) {
                            Icon(Icons.Default.ContentCut, contentDescription = "Prune old commits")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                commits.isEmpty() -> {
                    Text(
                        text = "No commits yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp, vertical = 12.dp
                        ),
                    ) {
                        itemsIndexed(commits) { index, commit ->
                            CommitCard(
                                commit = commit,
                                index = index,
                                formattedTime = vm.formatTimestamp(commit.timestamp),
                                shortId = vm.shortHash(commit.id),
                                onTap = { vm.showDiff(commit.id, commit.message, index) },
                                onRestore = { vm.restoreCommit(commit.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (diffResult != null) {
        DiffDialog(
            message = diffMessage,
            result = diffResult!!,
            onDismiss = { vm.clearDiff() },
        )
    }
}

@Composable
private fun CommitCard(
    commit: com.ztune.libretune.core.git.TuneCommit,
    index: Int,
    formattedTime: String,
    shortId: String,
    onTap: () -> Unit,
    onRestore: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = commit.message,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = shortId,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRestore) {
                Icon(Icons.Default.HistoryRestore, contentDescription = null, modifier = Modifier.height(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Restore this version")
            }
        }
    }
}

@Composable
private fun DiffDialog(
    message: String,
    result: com.ztune.libretune.core.git.TuneDiffResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Changes: $message", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            if (!result.hasChanges) {
                Text("No differences found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(modifier = Modifier.height(320.dp)) {
                    DiffSection("Constants", result.constants)
                    if (result.constants.isNotEmpty() && (result.tables.isNotEmpty() || result.curves.isNotEmpty())) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                    DiffSection("Tables", result.tables)
                    if (result.tables.isNotEmpty() && result.curves.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                    DiffSection("Curves", result.curves)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun DiffSection(title: String, entries: Map<String, ChangedValue>) {
    if (entries.isEmpty()) return
    Text(
        text = "$title (${entries.size})",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    entries.forEach { (name, change) ->
        Row(modifier = Modifier.padding(vertical = 2.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(120.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${change.oldValue}  →  ${change.newValue}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
