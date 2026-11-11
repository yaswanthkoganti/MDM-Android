// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android.engine

import app.mdm.android.data.DownloadItem
import app.mdm.android.data.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.IOException

/** One libtorrent session for the whole app (DHT, trackers, uTP, peer exchange come with it). */
object TorrentEngine {
    val session: SessionManager by lazy { SessionManager().also { it.start() } }
}

/**
 * Downloads a magnet link, a .torrent URL or an imported .torrent file with libtorrent4j.
 * Pause = remove the torrent from the session (data stays on disk); resume = add it again and
 * libtorrent re-checks the existing pieces, so no progress is lost.
 */
class TorrentTask(
    private val initial: DownloadItem,
    private val client: OkHttpClient,
    private val metaFile: File,     // cached .torrent metadata (written after the first fetch)
    private val saveDir: File,
    private val cacheDir: File,
    private val speedLimitBps: Long,
    private val onProgress: (DownloadItem) -> Unit,
) : Runner {
    @Volatile private var stopped = false
    private var item = initial

    override fun stop() { stopped = true }

    override suspend fun run(): DownloadItem = withContext(Dispatchers.IO) {
        try {
            runInner()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {   // includes UnsatisfiedLinkError if the native library is missing
            item.copy(status = Status.FAILED, error = e.message ?: e.javaClass.simpleName,
                speedBps = 0, connections = 0)
        }
    }

    private suspend fun runInner(): DownloadItem {
        val sm = TorrentEngine.session
        onProgress(item.copy(status = Status.DOWNLOADING, speedBps = 0, connections = 0))

        val ti = loadInfo(sm)
        if (stopped) return item.copy(status = Status.PAUSED, speedBps = 0, connections = 0)
        if (ti == null) return item.copy(status = Status.FAILED,
            error = "Could not get torrent metadata (no peers or bad link)", speedBps = 0, connections = 0)

        item = item.copy(totalBytes = ti.totalSize(), fileName = if (item.autoName) ti.name() else item.fileName)
        saveDir.mkdirs()
        sm.downloadRateLimit(if (speedLimitBps > 0) speedLimitBps.toInt() else 0)
        sm.download(ti, saveDir)
        val hash = ti.infoHash()

        var finished = false
        while (!stopped) {
            val h = sm.find(hash)
            if (h != null && h.isValid) {
                val st = h.status()
                onProgress(item.copy(
                    status = Status.DOWNLOADING,
                    downloadedBytes = st.totalDone(),
                    speedBps = st.downloadPayloadRate().toLong(),
                    connections = st.numPeers(),
                ))
                if (st.isFinished || st.isSeeding) { finished = true; break }
            }
            delay(500)
        }

        // Leave the session either way (no seeding in this version); files stay on disk.
        sm.find(hash)?.let { sm.remove(it) }
        delay(700)   // let libtorrent release the file handles before we copy the files

        if (!finished) return item.copy(status = Status.PAUSED, speedBps = 0, connections = 0)

        val fs = ti.files()
        val rels = (0 until fs.numFiles()).map { fs.filePath(it) }
            .filter { !it.startsWith(".pad") && File(saveDir, it).isFile }
        if (rels.isEmpty()) return item.copy(status = Status.FAILED, error = "Torrent finished but no files found",
            speedBps = 0, connections = 0)
        return item.copy(status = Status.COMPLETED, files = rels, downloadedBytes = ti.totalSize(),
            totalBytes = ti.totalSize(), speedBps = 0, connections = 0)
    }

    private fun loadInfo(sm: SessionManager): TorrentInfo? {
        if (metaFile.exists()) return TorrentInfo.bdecode(metaFile.readBytes())
        val url = item.url
        val data: ByteArray? = when {
            url.startsWith("magnet:", ignoreCase = true) -> {
                cacheDir.mkdirs()
                sm.fetchMagnet(url, 90, cacheDir)      // waits for peers/DHT to deliver the metadata
            }
            url.startsWith("http", ignoreCase = true) -> fetchTorrentFile(url)
            else -> null
        }
        if (data == null) return null
        metaFile.parentFile?.mkdirs()
        metaFile.writeBytes(data)
        return TorrentInfo.bdecode(data)
    }

    private fun fetchTorrentFile(url: String): ByteArray {
        client.newCall(Request.Builder().url(url).header("User-Agent", "MisterDM/0.2").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val b = r.body ?: throw IOException("Empty response")
            if (b.contentLength() > 20L * 1024 * 1024) throw IOException("Torrent file too large")
            return b.bytes()
        }
    }
}
