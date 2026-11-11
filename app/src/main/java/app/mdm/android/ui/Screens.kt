// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mdm.android.data.AppSettings
import app.mdm.android.data.DownloadItem
import app.mdm.android.data.FileCategory
import app.mdm.android.data.Kind
import app.mdm.android.data.ProxyKind
import app.mdm.android.data.Status
import app.mdm.android.engine.AddMode
import app.mdm.android.engine.DownloadManager
import app.mdm.android.engine.NewDownload
import app.mdm.android.util.AppInfo
import app.mdm.android.util.Fmt
import app.mdm.android.util.URL_REGEX
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

enum class StatusFilter(val label: String) {
    ALL("All"), ACTIVE("Active"), PAUSED("Paused"), DONE("Completed"), FAILED("Failed")
}

@Composable
fun MdmTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun MdmRoot(
    mgr: DownloadManager,
    incoming: StateFlow<List<String>>,
    clip: StateFlow<String?>,
    onIncomingDone: () -> Unit,
    onClipDone: () -> Unit,
) {
    val items by mgr.items.collectAsStateWithLifecycle()
    val settings by mgr.settings.collectAsStateWithLifecycle()
    val inc by incoming.collectAsStateWithLifecycle()
    val clipText by clip.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addInitial by remember { mutableStateOf("") }
    var browserUrl by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val err = mgr.addTorrentFile(uri)
            Toast.makeText(ctx, err ?: "Torrent added", Toast.LENGTH_SHORT).show()
        }
    }

    val browsing = browserUrl
    if (browsing != null) {
        BrowserScreen(mgr, browsing) { browserUrl = null }
    } else if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(settings, mgr::updateSettings) { showSettings = false }
    } else {
        MainScreen(
            mgr = mgr,
            all = items,
            onOpenSettings = { showSettings = true },
            onAdd = { addInitial = ""; addOpen = true },
            onPickTorrent = { picker.launch(arrayOf("*/*")) },
            onOpenBrowser = { browserUrl = "" },
            onUpdateVideo = {
                Toast.makeText(ctx, "Updating yt-dlp…", Toast.LENGTH_SHORT).show()
                mgr.updateVideoEngine { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
            },
        )
    }

    val onAddConfirm: (AddForm) -> Unit = { f ->
        mgr.add(
            f.urls.map { NewDownload(it, if (f.urls.size == 1) f.name else null, f.user, f.pass, f.sha, f.mode, f.height) },
            f.startNow,
        )
    }

    if (inc.isNotEmpty()) {
        AddDialog(initial = inc.joinToString("\n"), onDismiss = onIncomingDone, onConfirm = onAddConfirm,
            onBrowse = { browserUrl = it })
    } else if (addOpen) {
        AddDialog(initial = addInitial, onDismiss = { addOpen = false }, onConfirm = onAddConfirm,
            onBrowse = { browserUrl = it })
    }

    clipText?.let { url ->
        AlertDialog(
            onDismissRequest = onClipDone,
            title = { Text("Download copied link?") },
            text = { Text(url, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { addInitial = url; addOpen = true; onClipDone() }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = onClipDone) { Text("Ignore") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mgr: DownloadManager,
    all: List<DownloadItem>,
    onOpenSettings: () -> Unit,
    onAdd: () -> Unit,
    onPickTorrent: () -> Unit,
    onOpenBrowser: () -> Unit,
    onUpdateVideo: () -> Unit,
) {
    var statusFilter by remember { mutableStateOf(StatusFilter.ALL) }
    var catFilter by remember { mutableStateOf<FileCategory?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val shown = all.filter { i ->
        val statusOk = when (statusFilter) {
            StatusFilter.ALL -> true
            StatusFilter.ACTIVE -> i.status == Status.DOWNLOADING || i.status == Status.QUEUED
            StatusFilter.PAUSED -> i.status == Status.PAUSED
            StatusFilter.DONE -> i.status == Status.COMPLETED
            StatusFilter.FAILED -> i.status == Status.FAILED
        }
        statusOk && (catFilter == null || i.category == catFilter)
    }.sortedByDescending { it.addedAt }

    val active = all.filter { it.status == Status.DOWNLOADING }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mister DM") },
                actions = {
                    IconButton(onClick = onOpenBrowser) { Icon(Icons.Default.Language, "Browser") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Add .torrent file") }, onClick = { menuOpen = false; onPickTorrent() })
                        DropdownMenuItem(text = { Text("Update video engine") }, onClick = { menuOpen = false; onUpdateVideo() })
                        DropdownMenuItem(text = { Text("Start queue") }, onClick = { menuOpen = false; mgr.startQueue() })
                        DropdownMenuItem(text = { Text("Pause all") }, onClick = { menuOpen = false; mgr.stopAll() })
                        DropdownMenuItem(text = { Text("Clear completed") }, onClick = { menuOpen = false; mgr.clearCompleted() })
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add download") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "${active.size} active • ${Fmt.speed(active.sumOf { it.speedBps })} • ${all.size} total",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(StatusFilter.values().toList()) { f ->
                    FilterChip(selected = statusFilter == f, onClick = { statusFilter = f }, label = { Text(f.label) })
                }
            }
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FileCategory.values().toList()) { c ->
                    FilterChip(
                        selected = catFilter == c,
                        onClick = { catFilter = if (catFilter == c) null else c },
                        label = { Text(c.label) },
                    )
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No downloads here.\nTap + or share a link to Mister DM.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { DownloadCard(it, mgr) }
                }
            }
        }
    }
}

private fun kindTag(i: DownloadItem): String = when (i.kind) {
    Kind.HTTP -> i.category.label
    Kind.TORRENT -> "Torrent"
    Kind.VIDEO -> if (i.audioOnly) "Audio" else "Video"
}

private fun subtitle(i: DownloadItem): String = when (i.status) {
    Status.DOWNLOADING -> {
        val pctVal = (i.progress * 100).toInt()
        val size = if (i.totalBytes > 0) "${Fmt.bytes(i.downloadedBytes)} / ${Fmt.bytes(i.totalBytes)} ($pctVal%)"
        else if (i.percent >= 0f) "$pctVal%" else Fmt.bytes(i.downloadedBytes)
        val speed = if (i.speedBps > 0) " • ${Fmt.speed(i.speedBps)}" else ""
        val eta = if (i.speedBps > 0 && i.totalBytes > 0)
            " • ETA " + Fmt.eta((i.totalBytes - i.downloadedBytes) / i.speedBps) else ""
        val extra = when (i.kind) {
            Kind.HTTP -> " • ${i.connections} conn"
            Kind.TORRENT -> " • ${i.connections} peers"
            Kind.VIDEO -> ""
        }
        if (i.kind != Kind.HTTP && i.speedBps == 0L && i.downloadedBytes == 0L && i.percent < 0f)
            "${kindTag(i)} • starting…" else "$size$speed$eta$extra"
    }
    Status.QUEUED -> "Queued • ${kindTag(i)}"
    Status.PAUSED -> "Paused • " + (if (i.percent >= 0f) "${i.percent.toInt()}%" else "${Fmt.bytes(i.downloadedBytes)} / ${Fmt.bytes(i.totalBytes)}")
    Status.FAILED -> "Failed: ${i.error ?: "unknown error"}"
    Status.COMPLETED -> Fmt.bytes(i.totalBytes) + " • " + kindTag(i) +
        (if (i.checksumOk == true) " • checksum OK" else "")
}

private fun openFile(ctx: Context, i: DownloadItem) {
    val uri = i.contentUri?.let { Uri.parse(it) } ?: return
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, i.mime ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun DownloadCard(item: DownloadItem, mgr: DownloadManager) {
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    Text(subtitle(item), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (item.status) {
                    Status.DOWNLOADING, Status.QUEUED ->
                        IconButton(onClick = { mgr.pause(item.id) }) { Icon(Icons.Default.Pause, "Pause") }
                    Status.PAUSED, Status.FAILED ->
                        IconButton(onClick = { mgr.resume(item.id) }) { Icon(Icons.Default.PlayArrow, "Resume") }
                    Status.COMPLETED ->
                        TextButton(onClick = { openFile(ctx, item) }) { Text("Open") }
                }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
            }
            if (item.status != Status.COMPLETED) {
                Spacer(Modifier.height(6.dp))
                if (item.totalBytes > 0 || item.percent >= 0f) {
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                } else if (item.status == Status.DOWNLOADING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove download?") },
            text = { Text(item.fileName) },
            confirmButton = {
                Row {
                    TextButton(onClick = { confirmDelete = false; mgr.remove(item.id, false) }) { Text("Remove from list") }
                    if (item.status == Status.COMPLETED) {
                        TextButton(onClick = { confirmDelete = false; mgr.remove(item.id, true) }) { Text("Delete file too") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

class AddForm(
    val urls: List<String>,
    val name: String,
    val user: String,
    val pass: String,
    val sha: String,
    val startNow: Boolean,
    val mode: AddMode,
    val height: Int,
)

private fun modeLabel(m: AddMode) = when (m) {
    AddMode.AUTO -> "Auto"
    AddMode.FILE -> "File"
    AddMode.VIDEO -> "Video"
    AddMode.AUDIO -> "Audio (MP3)"
}

@Composable
fun AddDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (AddForm) -> Unit,
    onBrowse: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var startNow by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(AddMode.AUTO) }
    var height by remember { mutableStateOf(0) }
    val urls = remember(text) { URL_REGEX.findAll(text).map { it.value }.distinct().toList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add download") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Link(s) or magnet, one per line") },
                    minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (urls.isEmpty()) "No http(s) or magnet link found" else "${urls.size} link(s) detected",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddMode.values().forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(modeLabel(m)) })
                    }
                }
                if (mode == AddMode.VIDEO) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 2160, 1440, 1080, 720, 480, 360).forEach { h ->
                            FilterChip(selected = height == h, onClick = { height = h },
                                label = { Text(if (h == 0) "Best" else "${h}p") })
                        }
                    }
                }
                if (urls.size <= 1) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Save as (optional)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = startNow, onCheckedChange = { startNow = it })
                    Text("Start immediately")
                }
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "Hide advanced" else "Advanced (login, checksum)")
                }
                if (advanced) {
                    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Password") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = sha, onValueChange = { sha = it }, label = { Text("SHA-256 (files only)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(enabled = urls.isNotEmpty(), onClick = {
                onConfirm(AddForm(urls, name, user, pass, sha, startNow, mode, height))
                onDismiss()
            }) { Text("Add") }
        },
        dismissButton = {
            Row {
                val first = urls.firstOrNull { it.startsWith("http") }
                if (first != null) {
                    TextButton(onClick = { onBrowse(first); onDismiss() }) { Text("Open in browser") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(s: AppSettings, onChange: (AppSettings) -> Unit, onBack: () -> Unit) {
    var speedText by remember { mutableStateOf(s.speedLimitKBps.toString()) }
    var host by remember { mutableStateOf(s.proxyHost) }
    var port by remember { mutableStateOf(if (s.proxyPort > 0) s.proxyPort.toString() else "") }
    var showAbout by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LabeledSlider("Connections per file: ${s.maxConnections}", s.maxConnections, 1, 32) {
                onChange(s.copy(maxConnections = it))
            }
            LabeledSlider("Simultaneous downloads: ${s.maxSimultaneous}", s.maxSimultaneous, 1, 8) {
                onChange(s.copy(maxSimultaneous = it))
            }
            OutlinedTextField(
                value = speedText,
                onValueChange = {
                    speedText = it.filter(Char::isDigit).take(7)
                    onChange(s.copy(speedLimitKBps = speedText.toIntOrNull() ?: 0))
                },
                label = { Text("Speed limit, KB/s (0 = unlimited)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Download on Wi-Fi / unmetered only", s.wifiOnly) { onChange(s.copy(wifiOnly = it)) }
            SwitchRow("Offer to download copied links", s.clipboardOffer) { onChange(s.copy(clipboardOffer = it)) }

            Text("Proxy (applies to new downloads)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProxyKind.values().forEach { k ->
                    FilterChip(selected = s.proxyKind == k, onClick = { onChange(s.copy(proxyKind = k)) },
                        label = { Text(k.name) })
                }
            }
            if (s.proxyKind != ProxyKind.NONE) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; onChange(s.copy(proxyHost = it.trim())) },
                    label = { Text("Host") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = {
                        port = it.filter(Char::isDigit).take(5)
                        onChange(s.copy(proxyPort = port.toIntOrNull() ?: 0))
                    },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(onClick = { showAbout = true }) { Text("About & open-source licences") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("Mister DM for Android") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Free software under the GNU GPL v3 or later (${AppInfo.LICENSE_ID}). " +
                        "It comes with no warranty. You may share and modify it under the same licence.")
                    Text("Source code: ${AppInfo.SOURCE_URL}")
                    Text("Built with: youtubedl-android / yt-dlp (GPL-3.0), libtorrent4j (MIT), OkHttp, " +
                        "AndroidX, Jetpack Compose, Kotlin coroutines and serialization (Apache-2.0), FFmpeg.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppInfo.SOURCE_URL))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(ctx, "No browser found", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Open source code") }
            },
            dismissButton = { TextButton(onClick = { showAbout = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Column {
        Text(label)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = max - min - 1,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
