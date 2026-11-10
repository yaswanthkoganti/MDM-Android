// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android.engine

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.core.content.ContextCompat
import app.mdm.android.DownloadService
import app.mdm.android.Notifier
import app.mdm.android.data.AppSettings
import app.mdm.android.data.DownloadItem
import app.mdm.android.data.DownloadStore
import app.mdm.android.data.Kind
import app.mdm.android.data.SettingsStore
import app.mdm.android.data.Status
import app.mdm.android.util.Hashing
import app.mdm.android.util.Names
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.libtorrent4j.TorrentInfo
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class AddMode { AUTO, FILE, VIDEO, AUDIO }

class NewDownload(
    val url: String,
    val name: String? = null,
    val user: String? = null,
    val pass: String? = null,
    val sha256: String? = null,
    val mode: AddMode = AddMode.AUTO,
    val height: Int = 0,
)

/** Owns the download list, the queue, settings and running tasks. One instance per process. */
class DownloadManager(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = DownloadStore(app)
    private val settingsStore = SettingsStore(app)
    private val limiter = SpeedLimiter()

    private val _items = MutableStateFlow(
        store.load().map {
            if (it.status == Status.DOWNLOADING) it.copy(status = Status.PAUSED, speedBps = 0, connections = 0) else it
        }
    )
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private class Handle(val task: Runner, val job: Job)

    private val tasks = ConcurrentHashMap<String, Handle>()
    private val dirty = AtomicBoolean(false)
    private var client: OkHttpClient = HttpClients.build(_settings.value)
    private val cm: ConnectivityManager = app.getSystemService(ConnectivityManager::class.java)

    init {
        limiter.limitBps = _settings.value.speedLimitKBps * 1024L
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(2000)
                if (dirty.getAndSet(false)) store.save(_items.value)
            }
        }
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = connectivityChanged()
            override fun onLost(network: Network) = connectivityChanged()
        })
    }

    // ---- public API ----------------------------------------------------------------------------

    fun add(list: List<NewDownload>, startNow: Boolean) {
        val now = System.currentTimeMillis()
        val created = list.mapIndexed { i, n ->
            val name = n.name?.takeIf { it.isNotBlank() }
            val kind = when (n.mode) {
                AddMode.VIDEO, AddMode.AUDIO -> if (Names.isTorrentLike(n.url)) Kind.TORRENT else Kind.VIDEO
                AddMode.FILE -> if (Names.isTorrentLike(n.url)) Kind.TORRENT else Kind.HTTP
                AddMode.AUTO -> Names.detectKind(n.url)
            }
            val guessed = when (kind) {
                Kind.TORRENT -> Names.magnetName(n.url) ?: if (n.url.startsWith("magnet:", true)) "Torrent" else Names.guess(n.url)
                Kind.VIDEO -> "Video from " + (runCatching { Uri.parse(n.url).host }.getOrNull() ?: "web")
                Kind.HTTP -> Names.guess(n.url)
            }
            DownloadItem(
                id = UUID.randomUUID().toString(),
                url = n.url,
                fileName = if (name != null) Names.sanitize(name) else guessed,
                autoName = name == null,
                username = n.user?.takeIf { it.isNotBlank() },
                password = n.pass?.takeIf { it.isNotBlank() },
                sha256 = if (kind == Kind.HTTP) n.sha256?.trim()?.takeIf { it.isNotBlank() } else null,
                status = if (startNow) Status.QUEUED else Status.PAUSED,
                addedAt = now + i,
                kind = kind,
                audioOnly = kind == Kind.VIDEO && n.mode == AddMode.AUDIO,
                videoHeight = n.height,
            )
        }
        _items.update { it + created }
        persistNow()
        pump()
    }

    /** Imports a .torrent file (from the file picker or "open with"). Returns an error message or null. */
    fun addTorrentFile(uri: Uri): String? {
        return try {
            val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return "Cannot read that file"
            val ti = TorrentInfo.bdecode(bytes)
            val id = UUID.randomUUID().toString()
            val f = torrentFile(id)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            val item = DownloadItem(
                id = id, url = "torrent:" + ti.name(), fileName = ti.name(), kind = Kind.TORRENT,
                totalBytes = ti.totalSize(), status = Status.QUEUED,
            )
            _items.update { it + item }
            persistNow()
            pump()
            null
        } catch (e: Throwable) {
            "Not a valid .torrent file"
        }
    }

    fun updateVideoEngine(onDone: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val msg = VideoEngine.update(app)
            withContext(Dispatchers.Main) { onDone(msg) }
        }
    }

    fun pause(id: String) {
        update(id) {
            if (it.status == Status.DOWNLOADING || it.status == Status.QUEUED)
                it.copy(status = Status.PAUSED, speedBps = 0, connections = 0) else it
        }
        tasks[id]?.task?.stop()
        persistNow()
    }

    fun resume(id: String) {
        update(id) {
            if (it.status == Status.PAUSED || it.status == Status.FAILED)
                it.copy(status = Status.QUEUED, error = null) else it
        }
        persistNow()
        pump()
    }

    fun startQueue() {
        _items.update { l ->
            l.map {
                if (it.status == Status.PAUSED || it.status == Status.FAILED)
                    it.copy(status = Status.QUEUED, error = null) else it
            }
        }
        persistNow()
        pump()
    }

    fun stopAll() {
        _items.update { l ->
            l.map {
                if (it.status == Status.DOWNLOADING || it.status == Status.QUEUED)
                    it.copy(status = Status.PAUSED, speedBps = 0, connections = 0) else it
            }
        }
        tasks.values.forEach { it.task.stop() }
        persistNow()
    }

    fun remove(id: String, deleteFile: Boolean) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        tasks[id]?.task?.stop()
        _items.update { l -> l.filter { it.id != id } }
        scope.launch(Dispatchers.IO) {
            tasks[id]?.job?.join()
            tempFile(id).delete()
            workDir(id).deleteRecursively()
            torrentFile(id).delete()
            if (deleteFile) {
                item.contentUri?.let { runCatching { app.contentResolver.delete(Uri.parse(it), null, null) } }
            }
            persistNow()
        }
    }

    fun clearCompleted() {
        _items.update { l -> l.filter { it.status != Status.COMPLETED } }
        persistNow()
    }

    fun updateSettings(s: AppSettings) {
        val proxyChanged = s.proxyKind != _settings.value.proxyKind ||
            s.proxyHost != _settings.value.proxyHost || s.proxyPort != _settings.value.proxyPort
        _settings.value = s
        settingsStore.save(s)
        limiter.limitBps = s.speedLimitKBps * 1024L
        if (proxyChanged) client = HttpClients.build(s)   // applies to downloads started afterwards
        connectivityChanged()
    }

    // ---- scheduling ----------------------------------------------------------------------------

    @Synchronized
    private fun pump() {
        val s = _settings.value
        if (s.wifiOnly && !unmetered()) return
        var running = tasks.size
        val waiting = _items.value.filter { it.status == Status.QUEUED }.sortedBy { it.addedAt }
        for (item in waiting) {
            if (running >= s.maxSimultaneous) break
            if (tasks.containsKey(item.id)) continue
            start(item)
            running++
        }
    }

    private fun start(item: DownloadItem) {
        update(item.id) { it.copy(status = Status.DOWNLOADING, error = null, speedBps = 0) }
        val cfg = _settings.value
        val cb: (DownloadItem) -> Unit = { p -> onProgress(p) }
        val running = item.copy(status = Status.DOWNLOADING)
        val task: Runner = when (item.kind) {
            Kind.HTTP -> DownloadTask(running, client, limiter, cfg.maxConnections, tempFile(item.id), cb)
            Kind.TORRENT -> TorrentTask(running, client, torrentFile(item.id), workDir(item.id),
                File(app.cacheDir, "magnet"), cfg.speedLimitKBps * 1024L, cb)
            Kind.VIDEO -> VideoTask(app, running, workDir(item.id), cfg.maxConnections, cb)
        }
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    task.run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    item.copy(status = Status.FAILED, error = e.message ?: "Error")
                }
                handleResult(result)
            } finally {
                tasks.remove(item.id)
            }
            pump()
        }
        tasks[item.id] = Handle(task, job)
        job.start()
        startService()
    }

    private fun onProgress(p: DownloadItem) {
        update(p.id) { cur ->
            if (cur.status != Status.DOWNLOADING) cur
            else cur.copy(
                downloadedBytes = p.downloadedBytes, totalBytes = p.totalBytes, speedBps = p.speedBps,
                connections = p.connections, segments = p.segments, acceptsRanges = p.acceptsRanges,
                fileName = p.fileName, mime = p.mime, percent = p.percent,
            )
        }
    }

    private fun handleResult(r: DownloadItem) {
        if (_items.value.none { it.id == r.id }) { tempFile(r.id).delete(); return }
        when (r.status) {
            Status.COMPLETED -> finalizeDownload(r)
            Status.FAILED -> update(r.id) {
                if (it.status == Status.DOWNLOADING)
                    it.copy(status = Status.FAILED, error = r.error, segments = r.segments,
                        downloadedBytes = r.downloadedBytes, totalBytes = r.totalBytes,
                        acceptsRanges = r.acceptsRanges, speedBps = 0, connections = 0)
                else it
            }
            else -> update(r.id) {
                if (it.status == Status.DOWNLOADING || it.status == Status.PAUSED || it.status == Status.QUEUED)
                    it.copy(
                        status = if (it.status == Status.DOWNLOADING) Status.PAUSED else it.status,
                        segments = r.segments, downloadedBytes = r.downloadedBytes, totalBytes = r.totalBytes,
                        acceptsRanges = r.acceptsRanges, fileName = r.fileName, mime = r.mime,
                        percent = r.percent, speedBps = 0, connections = 0)
                else it
            }
        }
        persistNow()
    }

    private fun finalizeDownload(r: DownloadItem) {
        if (r.kind == Kind.HTTP) finalizeHttp(r) else finalizeMulti(r)
    }

    /** Torrent / video: publish every output file (keeping folders) into Downloads/MisterDM. */
    private fun finalizeMulti(r: DownloadItem) {
        val base = workDir(r.id)
        var bestUri: Uri? = null
        var bestSize = -1L
        var bestMime = "application/octet-stream"
        var bestName = r.fileName
        for (rel in r.files) {
            val src = File(base, rel)
            if (!src.isFile) continue
            val name = rel.substringAfterLast('/')
            val sub = rel.substringBeforeLast('/', "")
            val size = src.length()
            val mime = Publisher.resolveMime(name, null)
            val uri = Publisher.publish(app, src, name, mime, sub)
            if (uri == null) {
                update(r.id) {
                    it.copy(status = Status.FAILED, error = "Could not save to Downloads folder",
                        speedBps = 0, connections = 0)
                }
                return
            }
            if (size > bestSize) { bestSize = size; bestUri = uri; bestMime = mime; bestName = name }
        }
        base.deleteRecursively()
        torrentFile(r.id).delete()
        val uri = bestUri
        if (uri == null) {
            update(r.id) { it.copy(status = Status.FAILED, error = "No output files found", speedBps = 0, connections = 0) }
            return
        }
        update(r.id) {
            it.copy(status = Status.COMPLETED, totalBytes = r.totalBytes, downloadedBytes = r.totalBytes,
                percent = if (r.kind == Kind.VIDEO) 100f else -1f, files = emptyList(), speedBps = 0, connections = 0,
                error = null, contentUri = uri.toString(), mime = bestMime,
                fileName = if (r.kind == Kind.VIDEO) bestName else r.fileName,
                completedAt = System.currentTimeMillis())
        }
        Notifier.complete(app, r.fileName, uri, bestMime)
    }

    private fun finalizeHttp(r: DownloadItem) {
        val temp = tempFile(r.id)
        var checksumOk: Boolean? = null
        if (!r.sha256.isNullOrBlank()) {
            checksumOk = Hashing.sha256(temp).equals(r.sha256.trim(), ignoreCase = true)
            if (checksumOk == false) {
                temp.delete()
                update(r.id) {
                    it.copy(status = Status.FAILED, error = "Checksum mismatch", segments = emptyList(),
                        downloadedBytes = 0, checksumOk = false, speedBps = 0, connections = 0)
                }
                return
            }
        }
        val mime = Publisher.resolveMime(r.fileName, r.mime)
        val uri = Publisher.publish(app, temp, r.fileName, mime)
        if (uri == null) {
            update(r.id) {
                it.copy(status = Status.FAILED, error = "Could not save to Downloads folder",
                    speedBps = 0, connections = 0)
            }
            return
        }
        update(r.id) {
            it.copy(status = Status.COMPLETED, totalBytes = r.totalBytes, downloadedBytes = r.totalBytes,
                segments = emptyList(), speedBps = 0, connections = 0, error = null, checksumOk = checksumOk,
                contentUri = uri.toString(), mime = mime, completedAt = System.currentTimeMillis())
        }
        Notifier.complete(app, r.fileName, uri, mime)
    }

    // ---- network -------------------------------------------------------------------------------

    private fun unmetered(): Boolean {
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    private fun connectivityChanged() {
        if (_settings.value.wifiOnly && !unmetered()) {
            // Move running downloads back to the queue; they resume when Wi-Fi returns.
            for ((id, h) in tasks) {
                update(id) { if (it.status == Status.DOWNLOADING) it.copy(status = Status.QUEUED, speedBps = 0) else it }
                h.task.stop()
            }
        } else {
            pump()
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun update(id: String, f: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }
        dirty.set(true)
    }

    private fun persistNow() {
        dirty.set(false)
        scope.launch(Dispatchers.IO) { store.save(_items.value) }
    }

    private fun tempFile(id: String): File {
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "partial")
        dir.mkdirs()
        return File(dir, "$id.part")
    }

    private fun workDir(id: String): File = File(File(app.getExternalFilesDir(null) ?: app.filesDir, "work"), id)

    private fun torrentFile(id: String): File = File(File(app.filesDir, "torrents"), "$id.torrent")

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (e: Exception) {
            // Starting a foreground service from the background can be refused; downloads still run.
        }
    }
}
