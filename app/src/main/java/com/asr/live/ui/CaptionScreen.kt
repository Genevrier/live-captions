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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import com.asr.live.model.*
import com.asr.live.pipeline.*
import com.asr.live.service.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionScreen(vm: CaptionViewModel, hasAudioPermission: Boolean, onRequestPermission: () -> Unit,
                  hasOverlayPermission: Boolean, onRequestOverlayPermission: () -> Unit) {
    val config by vm.config.collectAsState()
    val ready by vm.ready.collectAsState()
    val busy by vm.busy.collectAsState()
    val lines by vm.lines.collectAsState()
    val lifecycle by vm.lifecycle.collectAsState()
    val error by vm.error.collectAsState()
    val download by vm.download.collectAsState()
    val metrics by vm.metrics.collectAsState()
    val overlay by vm.overlay.collectAsState()
    val managed by vm.managed.collectAsState()
    var settings by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    var modelManager by remember { mutableStateOf(false) }
    val stopped = lifecycle == ListeningState.STOPPED
    val info = ModelCatalog.byId(config.modelId) ?: ModelCatalog.DEFAULT
    Scaffold(topBar = { TopAppBar(title = { Text("Live Captions") }, actions = {
        TextButton(onClick = { settings = true }) { Text("Settings") }
    }) }, bottomBar = {
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!hasAudioPermission) Button(onClick = onRequestPermission, modifier = Modifier.weight(1f)) { Text("Allow microphone") }
                else if (!ready && stopped) Button(onClick = vm::downloadRequired, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (busy) "Downloading…" else "Download required models") }
                else Button(onClick = vm::toggle, enabled = lifecycle != ListeningState.STOPPING && (!stopped || !busy), modifier = Modifier.weight(1f)) {
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
            Text("ASR: ${info.shortName} · ${info.chunkMs?.let { "$it ms" } ?: "VAD phrases"} · ${if (!stopped) metrics.backend else if (config.qnn && info.kind == EngineKind.NEMOTRON) "QNN requested · experimental / CPU fallback" else "CPU"} · ${config.threads} threads", style = MaterialTheme.typography.bodySmall)
            Text("Final: ${config.quality.label}", style = MaterialTheme.typography.bodySmall)
            Text("Provisional: ${if (config.quality == TranslationQuality.ML_KIT) "ML Kit on-device" else config.profile.fastBundle?.let { "$it · CPU" } ?: "off"}", style = MaterialTheme.typography.bodySmall)
            Text(if (ready) "Offline ready" else "Required pinned models: ~${vm.downloadMegabytes()} MB${if (config.quality == TranslationQuality.ML_KIT) " + ML Kit language pack" else ""}", style = MaterialTheme.typography.labelMedium)
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
                            Text(buildAnnotatedString {
                                if (caption.source.startsWith(caption.stableSource)) {
                                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(caption.stableSource) }
                                    append(caption.source.removePrefix(caption.stableSource))
                                } else append(caption.source)
                            }, style = MaterialTheme.typography.bodyLarge)
                            Text(caption.detail, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } }, title = { Text("Advanced settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Floating captions", style = MaterialTheme.typography.titleMedium)
            if (!hasOverlayPermission) {
                Text("Optional: allow display over other apps. In-app captions work without this permission.")
                TextButton(onClick = onRequestOverlayPermission) { Text("Allow floating captions") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = overlay.enabled, onCheckedChange = { vm.updateOverlay(overlay.copy(enabled = it)) }, enabled = hasOverlayPermission)
                Text("Show while listening")
            }
            Text("Background opacity: ${(overlay.opacity * 100).toInt()}%")
            Slider(value = overlay.opacity, onValueChange = { vm.updateOverlay(overlay.copy(opacity = it)) }, valueRange = 0f..0.9f)
            Text("Font size: ${overlay.fontSp} sp")
            Slider(value = overlay.fontSp.toFloat(), onValueChange = { vm.updateOverlay(overlay.copy(fontSp = it.toInt())) }, valueRange = 16f..40f, steps = 23)
            Text("Translation lines: ${overlay.lines}")
            Slider(value = overlay.lines.toFloat(), onValueChange = { vm.updateOverlay(overlay.copy(lines = it.toInt())) }, valueRange = 1f..4f, steps = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = overlay.source, onCheckedChange = { vm.updateOverlay(overlay.copy(source = it)) })
                Text("Show source transcript")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = overlay.touchThrough, onCheckedChange = { vm.updateOverlay(overlay.copy(touchThrough = it)) })
                Text("Touch-through")
            }
            Text("Turn touch-through off to drag. Android limits window opacity in touch-through mode. Use the listening notification to show or hide captions.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            TextButton(onClick = vm::downloadRequired, enabled = stopped && !busy) { Text("Verify / repair required models") }
            TextButton(onClick = { modelManager = true }) { Text("Download / remove models") }
            Text("Recognition model")
            vm.models().forEach { model -> TextButton(onClick = { vm.update(config.copy(modelId = model.id)) }, enabled = stopped && !busy) { Text((if (model.id == config.modelId) "✓ " else "") + model.displayName) } }
            Text("ASR backend")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = config.qnn, onCheckedChange = { vm.update(config.copy(qnn = it)) },
                    enabled = stopped && !busy && info.kind == EngineKind.NEMOTRON &&
                        android.os.Build.VERSION.SDK_INT >= 31 && BackendPolicy.qnnEligible(android.os.Build.SOC_MODEL, com.asr.live.BuildConfig.QNN_ENABLED))
                Text("QNN/NPU · experimental SM8750")
            }
            Text("CPU fallback is always downloaded. QNN has not been tested on this Honor phone.", style = MaterialTheme.typography.bodySmall)
            if (info.kind == EngineKind.NEMOTRON) {
                Text("Nemotron model chunk")
                ModelCatalog.chunkProfiles(config.qnn).forEach { model ->
                    val installed = managed.firstOrNull { it.id == model.id }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.update(config.copy(modelId = model.id)) },
                            modifier = Modifier.weight(1f),
                            enabled = stopped && !busy && (model.id == config.modelId || installed?.selectable == true)) {
                            Text((if (model.id == config.modelId) "✓ " else "") + ModelCatalog.chunkLabel(model.chunkMs))
                        }
                        if (!config.qnn && installed?.selectable != true) TextButton(onClick = { vm.downloadModel(model.id) }, enabled = stopped && !busy) {
                            Text(if (installed?.installed == true) "Test load" else "Download")
                        }
                    }
                }
                Text(if (config.qnn) "Only the 560 ms context is integrated. Other QNN chunk profiles are unavailable until hardware validation."
                    else "Each size downloads a separate ~475 MB model and passes a CPU load/decode test before selection. 560 ms is the initial default.", style = MaterialTheme.typography.bodySmall)
            }
            Text("CPU threads: ${config.threads}")
            Slider(value = config.threads.toFloat(), onValueChange = { vm.update(config.copy(threads = it.toInt())) }, valueRange = 1f..8f, steps = 6, enabled = stopped && !busy)
            Text("Translation quality")
            TranslationQuality.entries.forEach { quality -> TextButton(onClick = { vm.update(config.copy(quality = quality)) }, enabled = stopped && !busy) { Text((if (quality == config.quality) "✓ " else "") + quality.label) } }
            if (config.quality != TranslationQuality.ML_KIT) OutlinedTextField(value = config.glossary,
                onValueChange = { vm.update(config.copy(glossary = it.take(2000))) },
                enabled = stopped && !busy, label = { Text("Glossary: source -> target") },
                placeholder = { Text("晶圆 -> wafer\n套刻 -> overlay\n压印 -> imprint\n母模 -> master\n光刻胶 -> resist") }, minLines = 3)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = config.correction && config.profile.correctionSupported,
                    onCheckedChange = { vm.update(config.copy(correction = it)) },
                    enabled = stopped && !busy && config.profile.correctionSupported && info.kind == EngineKind.NEMOTRON)
                Text("Parakeet v3 endpoint correction")
            }
            Text(if (config.profile.correctionSupported) "Optional second hypothesis; skipped under load. CPU performance is device dependent." else "Parakeet correction is unavailable for Mandarin.", style = MaterialTheme.typography.bodySmall)
            Text("Audio stays in memory and is never uploaded or saved.", style = MaterialTheme.typography.bodySmall)
        }
    })
    if (modelManager) AlertDialog(onDismissRequest = { modelManager = false },
        confirmButton = { TextButton(onClick = { modelManager = false }) { Text("Done") } },
        title = { Text("Downloaded models") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Stop listening to change model files. Removing a required model disables Listen until downloaded again.")
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Downloading / verifying / test loading…") }
                if (download is ModelRepository.DownloadState.Running) {
                    val d = download as ModelRepository.DownloadState.Running
                    Text("${d.phase.name.lowercase()} · ${d.pct}% · ${d.bytes / 1_000_000}/${d.total / 1_000_000} MB")
                }
                if (download is ModelRepository.DownloadState.Failed) Text((download as ModelRepository.DownloadState.Failed).message)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                managed.forEach { model ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(model.label)
                    Text("~${model.bytes / 1_000_000} MB download · ${if (model.installed) "Installed" else "Not installed"}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { vm.downloadModel(model.id) }, enabled = stopped && !busy) { Text(if (model.installed) "Verify" else "Download") }
                        TextButton(onClick = { vm.removeModel(model.id) }, enabled = stopped && !busy && model.installed) { Text("Remove") }
                    }
                }
                Text("ML Kit language packs, when selected, are managed by Google Play services.", style = MaterialTheme.typography.bodySmall)
            }
        })
}

@Composable
private fun PerformancePanel(m: Performance) {
    val text = "${m.profile}\nASR: ${m.asr.ifBlank { "Not running" }} · ${m.backend} · chunk ${m.chunk}\n" +
        "Translator: ${m.translator}\nASR ${m.asrMs} ms · RTF ${"%.2f".format(m.asrRtf)}\n" +
        "Translation ${m.translationMs} ms · correction ${m.correctionMs} ms / RTF ${"%.2f".format(m.correctionRtf)} · skipped ${m.skippedCorrections}\n" +
        "Endpoint → provisional ${m.provisionalLatencyMs?.let { "$it ms" } ?: "—"} · final ${m.finalLatencyMs?.let { "$it ms" } ?: "—"}\n" +
        "Audio queue ${m.audioDepth} · translation ${m.provisionalDepth}+${m.finalDepth}\n" +
        "Caption backlog ${m.captionBacklogMs} ms · capture backlog ${m.backlogMs} ms\nDropped audio ${m.droppedAudioMs} ms · skipped translations ${m.skippedTranslations}\n" +
        "App + model RAM ≈ ${m.appPssKb / 1024} MiB PSS (includes QNN worker)\nMain-process native heap ≈ ${m.nativeHeapKb / 1024} MiB · sampled every 5 s"
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(bottom = 8.dp))
}
