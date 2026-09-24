package com.asr.live.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.asr.live.i18n.Languages
import com.asr.live.model.ModelInfo
import com.asr.live.model.ModelRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionScreen(
    vm: CaptionViewModel,
    hasAudioPermission: Boolean,
    onRequestPermission: () -> Unit,
) {
    val selected by vm.selected.collectAsState()
    val modelReady by vm.offlineReady.collectAsState()
    val running by vm.running.collectAsState()
    val lines by vm.lines.collectAsState()
    val partial by vm.partial.collectAsState()
    val error by vm.error.collectAsState()
    val status by vm.status.collectAsState()
    val download by vm.download.collectAsState()
    val spoken by vm.spoken.collectAsState()
    val target by vm.target.collectAsState()

    val info = remember(selected) { vm.selectedInfo() }
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            EngineTopBar(
                models = vm.models,
                selectedId = selected,
                running = running,
                onSelect = vm::select,
                onOpenSettings = { showSettings = true },
                onClear = vm::clear,
            )
        },
        floatingActionButton = {
            if (modelReady && hasAudioPermission) {
                ExtendedFloatingActionButton(
                    onClick = vm::toggle,
                    icon = { Icon(if (running) Icons.Filled.Stop else Icons.Filled.Mic, null) },
                    text = { Text(if (running) "Stop" else "Listen") },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            ConfigBar(info = info, spoken = spoken, target = target, status = status, offlineReady = modelReady)
            Box(Modifier.fillMaxSize()) {
                when {
                    !hasAudioPermission -> InfoCard(
                        title = "Microphone needed",
                        body = "This app captions speech around you on-device. Grant microphone access to start.",
                        buttonLabel = "Grant access",
                        onClick = onRequestPermission,
                    )

                    !modelReady -> ModelGate(
                        info = info,
                        download = download,
                        onDownload = { vm.download(info.id) },
                    )

                    else -> Transcript(lines = lines, partial = partial, running = running)
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            showSpoken = info.isMultilingual,
            showSwap = info.isMultilingual && target != Languages.OFF,
            spoken = spoken,
            target = target,
            onSpoken = vm::setSpoken,
            onTarget = vm::setTarget,
            onSwap = vm::swapLanguages,
            onDismiss = { showSettings = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineTopBar(
    models: List<ModelInfo>,
    selectedId: String,
    running: Boolean,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = models.firstOrNull { it.id == selectedId } ?: models.first()

    TopAppBar(
        title = { Text("Live Captions") },
        actions = {
            TextButton(onClick = { if (!running) expanded = true }) {
                Text(current.shortName)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose engine")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                models.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(m.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(m.tagline, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = {
                            onSelect(m.id)
                            expanded = false
                        },
                    )
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Translate, contentDescription = "Language & translation")
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear transcript")
            }
        },
    )
}

/** Thin banner under the bar: current spoken/translate config, or a transient status. */
@Composable
private fun ConfigBar(info: ModelInfo, spoken: String, target: String, status: String?, offlineReady: Boolean) {
    if (status != null) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    val sourceLabel = if (info.isMultilingual) Languages.name(spoken) else "English"
    val summary = when {
        target != Languages.OFF -> "$sourceLabel  →  ${Languages.name(target)} · ${info.shortName} / CPU · ${if (info.kind == com.asr.live.model.EngineKind.WHISPER && target == "en") "Whisper" else "ML Kit on-device"}${if (offlineReady) " · Offline ready" else ""}"
        info.isMultilingual -> sourceLabel
        else -> null
    }
    if (summary != null) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                summary,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Transcript(
    lines: List<com.asr.live.service.CaptionState.Line>,
    partial: String,
    running: Boolean,
) {
    if (lines.isEmpty() && partial.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                if (running) "Listening… speak and captions will appear here."
                else "Tap Listen to start captioning.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val itemCount = lines.size + if (partial.isNotEmpty()) 1 else 0
    LaunchedEffect(itemCount, partial) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(lines, key = { it.id }) { line ->
            Column {
                Text(line.text, style = MaterialTheme.typography.headlineSmall)
                if (line.original != null) {
                    Text(
                        line.original,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (partial.isNotEmpty()) {
            item(key = "partial") {
                Text(
                    partial,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    showSpoken: Boolean,
    showSwap: Boolean,
    spoken: String,
    target: String,
    onSpoken: (String) -> Unit,
    onTarget: (String) -> Unit,
    onSwap: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Language & translation") },
        text = {
            Column {
                if (showSpoken) {
                    LangRow(
                        label = "Spoken language",
                        valueCode = spoken,
                        options = Languages.list.map { it.code to it.name },
                        onPick = onSpoken,
                    )
                    HorizontalDivider()
                } else {
                    Text(
                        "This engine recognizes English. Pick a 'Multilingual' engine to caption other spoken languages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                LangRow(
                    label = "Translate to",
                    valueCode = target,
                    options = listOf(Languages.OFF to "Off") + Languages.list.map { it.code to it.name },
                    onPick = onTarget,
                )
                if (showSwap) {
                    TextButton(onClick = onSwap, modifier = Modifier.align(Alignment.End)) {
                        Icon(Icons.Filled.SwapVert, contentDescription = null)
                        Text("  Swap")
                    }
                }
            }
        },
    )
}

@Composable
private fun LangRow(
    label: String,
    valueCode: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(Languages.name(valueCode), color = MaterialTheme.colorScheme.primary)
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (code, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPick(code); open = false })
            }
        }
    }
}

@Composable
private fun ModelGate(
    info: ModelInfo,
    download: ModelRepository.DownloadState,
    onDownload: () -> Unit,
) {
    val running = download as? ModelRepository.DownloadState.Running
    val isThis = running?.id == info.id
    val failed = (download as? ModelRepository.DownloadState.Failed)?.takeIf { it.id == info.id }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(info.displayName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(info.tagline, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(
                    "~${info.approxMB} MB · one-time download, then fully offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))

                if (isThis && running != null) {
                    when (running.phase) {
                        ModelRepository.Phase.DOWNLOAD -> {
                            LinearProgressIndicator(
                                progress = { running.pct / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Downloading… ${running.pct}%")
                        }
                        ModelRepository.Phase.EXTRACT -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text("Extracting…")
                        }
                        ModelRepository.Phase.TRANSLATOR -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text("Downloading on-device translator…")
                        }
                    }
                } else {
                    Button(onClick = onDownload) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Text("  Download required model")
                    }
                    if (failed != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Download failed: ${failed.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    buttonLabel: String,
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onClick) { Text(buttonLabel) }
            }
        }
    }
}
