package com.asr.live.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.asr.live.model.*
import com.asr.live.pipeline.*
import com.asr.live.service.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionScreen(vm: CaptionViewModel, hasAudioPermission: Boolean, onRequestPermission: () -> Unit) {
    val config by vm.config.collectAsState()
    val ready by vm.ready.collectAsState()
    val busy by vm.busy.collectAsState()
    val lines by vm.lines.collectAsState()
    val lifecycle by vm.lifecycle.collectAsState()
    val error by vm.error.collectAsState()
    val download by vm.download.collectAsState()
    val metrics by vm.metrics.collectAsState()
    var settings by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    val stopped = lifecycle == ListeningState.STOPPED
    val info = ModelCatalog.byId(config.modelId) ?: ModelCatalog.DEFAULT
    Scaffold(topBar = { TopAppBar(title = { Text("Live Captions") }, actions = {
        TextButton(onClick = { settings = true }) { Text("Settings") }
    }) }, bottomBar = {
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!hasAudioPermission) Button(onClick = onRequestPermission, modifier = Modifier.weight(1f)) { Text("Allow microphone") }
                else if (!ready && stopped) Button(onClick = vm::downloadRequired, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (busy) "Downloading…" else "Download required models") }
                else Button(onClick = vm::toggle, enabled = lifecycle != ListeningState.STOPPING, modifier = Modifier.weight(1f)) {
                    Text(when (lifecycle) { ListeningState.STOPPED -> "Listen"; ListeningState.STOPPING -> "Stopping…"; else -> "Stop" })
                }
                TextButton(onClick = vm::clear) { Text("Clear") }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Box {
                TextButton(onClick = { profiles = true }, enabled = stopped && !busy) { Text(config.profile.label, style = MaterialTheme.typography.titleMedium) }
                DropdownMenu(expanded = profiles, onDismissRequest = { profiles = false }) {
                    Profile.entries.forEach { profile -> DropdownMenuItem(text = { Text(profile.label) }, onClick = {
                        vm.update(config.copy(profile = profile)); profiles = false
                    }) }
                }
            }
            Text("ASR: ${info.shortName} · ${if (info.kind == EngineKind.NEMOTRON) "560 ms · " else ""}CPU · ${config.threads} threads", style = MaterialTheme.typography.bodySmall)
            Text("Translation: ${config.quality.label}", style = MaterialTheme.typography.bodySmall)
            Text(if (ready) "Offline ready" else "Required models: ~${info.approxMB} MB ASR + translation", style = MaterialTheme.typography.labelMedium)
            if (download is ModelRepository.DownloadState.Running) {
                val d = download as ModelRepository.DownloadState.Running
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("${d.phase.name.lowercase()} · ${d.pct}%${if (d.total > 0) " · ${d.bytes / 1_000_000}/${d.total / 1_000_000} MB" else ""}")
            }
            if (download is ModelRepository.DownloadState.Failed) Text((download as ModelRepository.DownloadState.Failed).message, color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "Hide performance" else "Performance") }
            if (diagnostics) PerformancePanel(metrics)
            val list = rememberLazyListState()
            LaunchedEffect(lines.lastOrNull()?.key) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                if (lines.isEmpty()) item { Text(if (stopped) "Download models, then tap Listen." else "${lifecycle.name.lowercase()}…", Modifier.padding(vertical = 24.dp)) }
                items(lines, key = { it.key.id }) { caption ->
                    Card(colors = CardDefaults.cardColors(containerColor = if (caption.stage == CaptionStage.PROVISIONAL)
                        MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(caption.translation.ifBlank { "…" }, style = MaterialTheme.typography.headlineSmall)
                            Text(caption.source, style = MaterialTheme.typography.bodyLarge)
                            Text(caption.detail, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } }, title = { Text("Advanced settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Recognition model")
            vm.models().forEach { model -> TextButton(onClick = { vm.update(config.copy(modelId = model.id)) }, enabled = stopped && !busy) { Text((if (model.id == config.modelId) "✓ " else "") + model.displayName) } }
            Text("Backend: CPU")
            Text("CPU threads: ${config.threads}")
            Slider(value = config.threads.toFloat(), onValueChange = { vm.update(config.copy(threads = it.toInt())) }, valueRange = 1f..8f, steps = 6, enabled = stopped && !busy)
            Text("Translation quality")
            TranslationQuality.entries.forEach { quality -> TextButton(onClick = { vm.update(config.copy(quality = quality)) }, enabled = stopped && !busy) { Text(quality.label) } }
            Text("Correction: off")
            Text("Audio stays in memory and is never uploaded or saved.", style = MaterialTheme.typography.bodySmall)
        }
    })
}

@Composable
private fun PerformancePanel(m: Performance) {
    val text = "ASR ${m.asrMs} ms · RTF ${"%.2f".format(m.asrRtf)}\n" +
        "Translation ${m.translationMs} ms\n" +
        "Endpoint → provisional ${m.provisionalLatencyMs?.let { "$it ms" } ?: "—"} · final ${m.finalLatencyMs?.let { "$it ms" } ?: "—"}\n" +
        "Audio queue ${m.audioDepth} · translation ${m.provisionalDepth}+${m.finalDepth}\n" +
        "Capture backlog ${m.backlogMs} ms · dropped audio ${m.droppedAudioMs} ms · skipped translations ${m.skippedTranslations}"
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
}
