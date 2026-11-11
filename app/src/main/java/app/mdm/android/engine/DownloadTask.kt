// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android.engine

import app.mdm.android.data.DownloadItem
import app.mdm.android.data.SegState
import app.mdm.android.data.Status
import app.mdm.android.util.Names
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val MIN_SEG = 512L * 1024     // don't split files into pieces smaller than this
private const val MIN_STEAL = 256L * 1024   // an idle connection steals half of a segment with > 2x this left
private const val MAX_RETRY = 5
private const val UA = "Mozilla/5.0 (Linux; Android) MisterDM/0.1"

/** One connection's byte range [start, end). `end` shrinks when another connection steals its tail. */
class Seg(val start: Long, @Volatile var end: Long, @Volatile var pos: Long)

class Probe(val total: Long, val ranges: Boolean, val fileName: String?, val mime: String?)

/**
 * Downloads one file with several simultaneous HTTP Range connections, like MDM's engine:
 *  - splits the file into segments, one connection each (up to maxConnections);
 *  - when a connection finishes, it re-splits the slowest remaining segment (work stealing);
 *  - segment progress is reported so the download resumes byte-exactly after a pause or crash;
 *  - every connection retries with back-off, resuming from where it stopped.
 */
class DownloadTask(
    private val initial: DownloadItem,
    private val client: OkHttpClient,
    private val limiter: SpeedLimiter,
    private val maxConnections: Int,
    private val tempFile: File,
    private val onProgress: (DownloadItem) -> Unit,
) : Runner {
    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    @Volatile private var stopped = false
    @Volatile private var failure: Throwable? = null
    private val lock = Any()
    private val segs = ArrayList<Seg>()
    private val activeConns = AtomicInteger(0)
    private var item: DownloadItem = initial

    /** Pause / cancel: aborts every open connection; run() then returns PAUSED. */
    override fun stop() {
        stopped = true
        calls.forEach { it.cancel() }
    }

    override suspend fun run(): DownloadItem {
        try {
            prepare()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return item.copy(status = Status.FAILED, error = e.message ?: "Connection error",
                speedBps = 0, connections = 0)
        }
        if (stopped) return finish()

        val channel = withContext(Dispatchers.IO) { RandomAccessFile(tempFile, "rw").channel }
        try {
            coroutineScope {
                val ticker = launch { tick() }
                val first = synchronized(lock) { segs.filter { it.pos < it.end } }
                val workers = first.map { s -> async(Dispatchers.IO) { worker(s, channel) } }
                workers.awaitAll()
                ticker.cancel()
            }
        } finally {
            channel.close()
        }
        return finish()
    }

    // ---- setup -------------------------------------------------------------------------------

    private suspend fun prepare() = withContext(Dispatchers.IO) {
        val cur = initial
        val resumable = cur.acceptsRanges && cur.totalBytes > 0 && cur.segments.isNotEmpty() &&
            tempFile.exists() && tempFile.length() == cur.totalBytes
        if (resumable) {
            synchronized(lock) {
                segs.clear()
                cur.segments.forEach { segs.add(Seg(it.start, it.end, it.pos)) }
            }
            item = cur
        } else {
            tempFile.parentFile?.mkdirs()
            tempFile.delete()
            val p = probe(cur)
            val total = if (p.total > 0) p.total else -1L
            val n = if (p.ranges && total >= 2 * MIN_SEG)
                (total / MIN_SEG).coerceAtMost(maxConnections.toLong()).toInt().coerceAtLeast(1) else 1
            synchronized(lock) {
                segs.clear()
                if (total > 0) {
                    val part = total / n
                    for (i in 0 until n) {
                        val s = i * part
                        val e = if (i == n - 1) total else (i + 1) * part
                        segs.add(Seg(s, e, s))
                    }
                } else {
                    segs.add(Seg(0, Long.MAX_VALUE, 0))
                }
            }
            item = cur.copy(
                totalBytes = total,
                acceptsRanges = p.ranges,
                mime = p.mime ?: cur.mime,
                fileName = if (cur.autoName && p.fileName != null) p.fileName else cur.fileName,
            )
            RandomAccessFile(tempFile, "rw").use { if (total > 0) it.setLength(total) }
        }
        onProgress(snapshot(Status.DOWNLOADING))
    }

    private fun request(range: String?): Request {
        val b = Request.Builder().url(item.url)
            .header("User-Agent", item.userAgent?.takeIf { it.isNotBlank() } ?: UA)
            .header("Accept-Encoding", "identity")
        if (range != null) b.header("Range", range)
        item.referer?.takeIf { it.isNotBlank() }?.let { b.header("Referer", it) }
        item.cookie?.takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
        val user = item.username
        if (!user.isNullOrEmpty()) b.header("Authorization", Credentials.basic(user, item.password ?: ""))
        return b.build()
    }

    /** Asks for byte 0 only: a 206 + Content-Range tells us the size and that ranges work. */
    private fun probe(cur: DownloadItem): Probe {
        item = cur
        client.newCall(request("bytes=0-0")).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val cr = r.header("Content-Range")
            val total: Long
            val ranges: Boolean
            if (r.code == 206 && cr != null) {
                total = cr.substringAfter('/').toLongOrNull() ?: -1L
                ranges = total > 0
            } else {
                total = r.header("Content-Length")?.toLongOrNull() ?: -1L
                ranges = false
            }
            val name = Names.fromContentDisposition(r.header("Content-Disposition"))
                ?: Names.guess(r.request.url.toString())
            val mime = r.header("Content-Type")?.substringBefore(';')?.trim()
            return Probe(total, ranges, name, mime)
        }
    }

    // ---- progress ----------------------------------------------------------------------------

    private fun snapshot(status: Status, error: String? = null): DownloadItem {
        val (done, states) = synchronized(lock) {
            Pair(
                segs.sumOf { (minOf(it.pos, it.end) - it.start).coerceAtLeast(0L) },
                segs.map { SegState(it.start, it.end, it.pos) },
            )
        }
        return item.copy(status = status, downloadedBytes = done, segments = states, error = error)
    }

    private suspend fun tick() {
        var last = snapshot(Status.DOWNLOADING).downloadedBytes
        var lastT = System.nanoTime()
        var ema = 0.0
        while (true) {
            delay(500)
            val s = snapshot(Status.DOWNLOADING)
            val now = System.nanoTime()
            val inst = (s.downloadedBytes - last) * 1e9 / (now - lastT).coerceAtLeast(1)
            ema = if (ema == 0.0) inst else ema * 0.6 + inst * 0.4
            last = s.downloadedBytes
            lastT = now
            onProgress(s.copy(speedBps = ema.toLong(), connections = activeConns.get()))
        }
    }

    private fun finish(): DownloadItem {
        failure?.let {
            return snapshot(Status.FAILED, it.message ?: it.javaClass.simpleName)
                .copy(speedBps = 0, connections = 0)
        }
        val s = snapshot(Status.DOWNLOADING)
        val complete = synchronized(lock) { segs.isNotEmpty() && segs.all { it.pos >= it.end } }
        if (!complete) return s.copy(status = Status.PAUSED, speedBps = 0, connections = 0)
        val total = if (s.totalBytes > 0) s.totalBytes else s.downloadedBytes
        return s.copy(status = Status.COMPLETED, totalBytes = total, downloadedBytes = total,
            speedBps = 0, connections = 0, segments = emptyList())
    }

    // ---- workers -----------------------------------------------------------------------------

    private suspend fun worker(first: Seg, ch: FileChannel) {
        var seg: Seg? = first
        while (!stopped && failure == null) {
            val s = seg ?: steal() ?: return
            try {
                fetch(s, ch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!stopped) { failure = e; stop() }
                return
            }
            seg = null
        }
    }

    /** Re-splits the segment with the most bytes left; returns the new upper half, or null. */
    private fun steal(): Seg? {
        if (!item.acceptsRanges) return null
        return synchronized(lock) {
            var victim: Seg? = null
            var best = 0L
            for (s in segs) {
                if (s.end == Long.MAX_VALUE) continue
                val rem = s.end - s.pos
                if (rem > best) { best = rem; victim = s }
            }
            val v = victim ?: return@synchronized null
            if (best < 2 * MIN_STEAL) return@synchronized null
            val mid = v.pos + best / 2
            val ns = Seg(mid, v.end, mid)
            v.end = mid
            segs.add(ns)
            ns
        }
    }

    private suspend fun fetch(seg: Seg, ch: FileChannel) {
        var attempt = 0
        while (true) {
            try {
                fetchOnce(seg, ch)
                return
            } catch (e: IOException) {
                if (stopped) return
                attempt++
                if (!item.acceptsRanges || attempt > MAX_RETRY) throw e
                delay(1000L * attempt)
            }
        }
    }

    private suspend fun fetchOnce(seg: Seg, ch: FileChannel) {
        if (seg.pos >= seg.end) return
        val ranged = item.acceptsRanges
        val range = if (ranged) {
            "bytes=${seg.pos}-" + (if (seg.end == Long.MAX_VALUE) "" else (seg.end - 1).toString())
        } else null
        val call = client.newCall(request(range))
        calls.add(call)
        if (stopped) call.cancel()
        activeConns.incrementAndGet()
        try {
            call.execute().use { r ->
                if (ranged && r.code != 206) throw IOException("Server ignored range request (HTTP ${r.code})")
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body ?: throw IOException("Empty response")
                val src = body.byteStream()
                val buf = ByteArray(128 * 1024)
                while (true) {
                    if (stopped) return
                    val remain = seg.end - seg.pos
                    if (remain <= 0) break
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                    if (n < 0) {
                        if (seg.end == Long.MAX_VALUE) seg.end = seg.pos  // unknown size: EOF = done
                        break
                    }
                    limiter.acquire(n)
                    ch.write(ByteBuffer.wrap(buf, 0, n), seg.pos)
                    seg.pos += n
                }
            }
            if (seg.pos < seg.end) throw IOException("Connection closed early")
        } finally {
            activeConns.decrementAndGet()
            calls.remove(call)
        }
    }
}
