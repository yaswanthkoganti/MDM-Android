// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.mdm.android.engine.AddMode
import app.mdm.android.engine.DownloadManager
import app.mdm.android.engine.NewDownload
import app.mdm.android.util.FoundMedia
import app.mdm.android.util.MediaSniffer
import app.mdm.android.util.MediaType
import app.mdm.android.util.Names

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/** Page info read from WebView background threads, so it is not Compose state. */
private class PageInfo {
    @Volatile var url: String = ""
    @Volatile var title: String = ""
}

private class MdmBridge(private val onUrl: (String) -> Unit) {
    @JavascriptInterface
    fun report(urls: String) {
        urls.split('\n').forEach { if (it.isNotBlank()) onUrl(it.trim()) }
    }
}

private fun normalizeUrl(input: String): String {
    val t = input.trim()
    return when {
        t.startsWith("http://") || t.startsWith("https://") -> t
        t.contains(' ') || !t.contains('.') -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        else -> "https://$t"
    }
}

private fun suggestName(m: FoundMedia): String? {
    val base = Names.sanitize(m.title.ifBlank { "video" }).take(120)
    return when (m.type) {
        MediaType.STREAM -> base                       // yt-dlp appends the real extension
        else -> {
            val n = Names.guess(m.url)
            if (n.contains('.')) null                  // URL already has a proper file name
            else base + (if (m.type == MediaType.AUDIO) ".mp3" else ".mp4")
        }
    }
}

/**
 * In-app browser: open a page, play the video, and the app lists every media link it sees
 * (network requests + <video>/<audio> elements + file links). Downloads reuse the page's
 * Referer, cookies and User-Agent so protected links still work.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(mgr: DownloadManager, startUrl: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val info = remember { PageInfo() }
    val found = remember { mutableStateListOf<FoundMedia>() }

    var address by remember { mutableStateOf(startUrl.ifBlank { "https://duckduckgo.com" }) }
    var progress by remember { mutableStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var desktop by remember { mutableStateOf(false) }

    fun report(url: String, type: MediaType) {
        val page = info.url
        val title = info.title
        main.post {
            if (found.size < 100 && found.none { it.url == url }) found.add(FoundMedia(url, type, page, title))
        }
    }

    fun startDownload(m: FoundMedia, name: String?, ua: String?) {
        val cookie = CookieManager.getInstance().getCookie(m.url)
        val mode = if (m.type == MediaType.STREAM) AddMode.VIDEO else AddMode.FILE
        mgr.add(
            listOf(NewDownload(m.url, name = name, mode = mode, referer = m.referer.ifBlank { null },
                cookie = cookie, userAgent = ua)),
            true,
        )
        Toast.makeText(ctx, "Added to downloads", Toast.LENGTH_SHORT).show()
    }

    val web = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.allowFileAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(MdmBridge { u ->
                MediaSniffer.classify(u, includeFiles = true)?.let { report(u, it) }
            }, "MdmBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.method == "GET") {
                        val u = request.url.toString()
                        MediaSniffer.classify(u, includeFiles = false)?.let { report(u, it) }
                    }
                    return null   // let the WebView load it normally
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url.toString()
                    return when {
                        u.startsWith("http://") || u.startsWith("https://") -> false
                        u.startsWith("magnet:") -> {
                            mgr.add(listOf(NewDownload(u)), true)
                            Toast.makeText(ctx, "Torrent added", Toast.LENGTH_SHORT).show()
                            true
                        }
                        else -> true   // block intent:, market:, etc.
                    }
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    info.url = url
                    info.title = ""
                    address = url
                    found.clear()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    canBack = view.canGoBack()
                    canForward = view.canGoForward()
                    view.evaluateJavascript(MediaSniffer.SCAN_JS, null)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    canBack = view.canGoBack()
                    canForward = view.canGoForward()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                override fun onReceivedTitle(view: WebView, title: String) { info.title = title }
            }
            setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                // The page served a file (attachment / binary): grab it with the page's cookies.
                val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
                val m = FoundMedia(url, MediaType.FILE, info.url, info.title)
                main.post { startDownload(m, name, userAgent) }
            }
            loadUrl(normalizeUrl(startUrl.ifBlank { "https://duckduckgo.com" }))
        }
    }

    DisposableEffect(web) {
        onDispose {
            web.stopLoading()
            web.destroy()
        }
    }

    BackHandler { if (web.canGoBack()) web.goBack() else onClose() }

    Scaffold(
        topBar = {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close browser") }
                    OutlinedTextField(
                        value = address, onValueChange = { address = it },
                        singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { web.loadUrl(normalizeUrl(address)) }),
                    )
                    IconButton(onClick = { web.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (desktop) "Mobile site" else "Desktop site") },
                            onClick = {
                                menuOpen = false
                                desktop = !desktop
                                web.settings.userAgentString = if (desktop) DESKTOP_UA else null
                                web.settings.useWideViewPort = desktop
                                web.settings.loadWithOverviewMode = desktop
                                web.reload()
                            },
                        )
                        DropdownMenuItem(text = { Text("Clear found media") },
                            onClick = { menuOpen = false; found.clear() })
                    }
                }
                if (progress in 1..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { web.goBack() }, enabled = canBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                IconButton(onClick = { web.goForward() }, enabled = canForward) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Grab (${found.size})") },
                icon = { Icon(Icons.Default.Download, "Grab media") },
                onClick = { showList = true },
            )
        },
    ) { pad ->
        AndroidView(factory = { web }, modifier = Modifier.padding(pad).fillMaxWidth())
    }

    if (showList) {
        AlertDialog(
            onDismissRequest = { showList = false },
            title = { Text("Media on this page") },
            text = {
                Column {
                    if (found.isEmpty()) {
                        Text("Nothing detected yet. Start playing the video (or scroll the page), then open this list again.",
                            style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(found.toList(), key = { it.url }) { m ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(Names.guess(m.url), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.titleSmall)
                                        Text(m.type.label + " • " + (Uri.parse(m.url).host ?: ""),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = {
                                        startDownload(m, suggestName(m), web.settings.userAgentString)
                                        showList = false
                                    }) { Text("Download") }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Works on YouTube, Vimeo, X, TikTok, Reddit... where the page itself is the source.
                    val page = FoundMedia(web.url ?: address, MediaType.STREAM, "", web.title ?: "")
                    mgr.add(listOf(NewDownload(page.url, mode = AddMode.VIDEO,
                        userAgent = web.settings.userAgentString,
                        cookie = CookieManager.getInstance().getCookie(page.url))), true)
                    Toast.makeText(ctx, "Page added as a video download", Toast.LENGTH_SHORT).show()
                    showList = false
                }) { Text("Grab whole page") }
            },
            dismissButton = { TextButton(onClick = { showList = false }) { Text("Close") } },
        )
    }
}
