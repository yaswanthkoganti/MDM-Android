// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android.engine

import android.content.Context
import app.mdm.android.data.DownloadItem
import app.mdm.android.data.Status
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Lazy one-time setup of the bundled yt-dlp (python) and ffmpeg. Takes a few seconds on first use. */
object VideoEngine {
    @Volatile private var ready = false

    @Synchronized
    fun ensure(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
        ready = true
    }

    /** Pulls the newest yt-dlp so site extractors keep working. */
    fun update(ctx: Context): String {
        return try {
            ensure(ctx)
            YoutubeDL.getInstance().updateYoutubeDL(ctx)
            "Video engine (yt-dlp) updated"
        } catch (e: Throwable) {
            "Update failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}

/**
 * Downloads videos/audio from YouTube, Vimeo, TikTok, X, Instagram, Reddit and ~1000 other sites with yt-dlp.
 * Speed: concurrent fragment downloads (-N) for HLS/DASH, and 10 MB range chunks to dodge per-connection throttling.
 */
class VideoTask(
    private val ctx: Context,
    private val initial: DownloadItem,
    private val workDir: File,
    private val connections: Int,
    private val onProgress: (DownloadItem) -> Unit,
) : Runner {
    @Volatile private var stopped = false
    private var item = initial

    private val speedRe = Regex("at\\s+([\\d.]+)\\s*([KMG]?)i?B/s")
    private val destRe = Regex("Destination:\\s*(.+)")
    private val intermediateRe = Regex("\\.f\\d+\\.")

    override fun stop() {
        stopped = true
        try { YoutubeDL.getInstance().destroyProcessById(initial.id) } catch (_: Throwable) {}
    }

    override suspend fun run(): DownloadItem = withContext(Dispatchers.IO) {
        try {
            VideoEngine.ensure(ctx)
            if (stopped) return@withContext paused()
            workDir.mkdirs()

            val req = YoutubeDLRequest(item.url)
            val template = if (item.autoName) "%(title).150B.%(ext)s" else
                item.fileName.replace(Regex("\\.(mp4|mkv|webm|mp3|m4a)$", RegexOption.IGNORE_CASE), "")
                    .take(150).replace("%", "%%") + ".%(ext)s"
            req.addOption("-o", File(workDir, template).absolutePath)
            item.referer?.takeIf { it.isNotBlank() }?.let { req.addOption("--referer", it) }
            item.userAgent?.takeIf { it.isNotBlank() }?.let { req.addOption("--user-agent", it) }
            item.cookie?.takeIf { it.isNotBlank() }?.let { req.addOption("--add-header", "Cookie:$it") }
            req.addOption("--no-playlist")
            req.addOption("--no-mtime")
            req.addOption("-N", connections.coerceIn(1, 16).toString())
            req.addOption("--http-chunk-size", "10M")
            if (item.audioOnly) {
                req.addOption("-f", "ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
            } else {
                val h = item.videoHeight
                val fmt = if (h > 0) "bv*[height<=$h]+ba/b[height<=$h]/b" else "bv*+ba/b"
                req.addOption("-f", fmt)
                req.addOption("--merge-output-format", "mp4")
            }

            YoutubeDL.getInstance().execute(
                request = req,
                processId = item.id,
                callback = { progress, _, line -> report(progress, line) },
            )
            if (stopped) return@withContext paused()

            val outputs = workDir.walkTopDown().filter { it.isFile && !isTemp(it.name) && it.length() > 0 }.toList()
            if (outputs.isEmpty()) {
                return@withContext item.copy(status = Status.FAILED, error = "yt-dlp produced no file",
                    speedBps = 0, connections = 0)
            }
            val total = outputs.sumOf { it.length() }
            val biggest = outputs.maxByOrNull { it.length() }!!
            item.copy(
                status = Status.COMPLETED, files = outputs.map { it.relativeTo(workDir).path },
                totalBytes = total, downloadedBytes = total, percent = 100f,
                fileName = biggest.name, speedBps = 0, connections = 0,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (stopped) paused()
            else item.copy(status = Status.FAILED,
                error = (e.message ?: e.javaClass.simpleName).lineSequence().firstOrNull { it.isNotBlank() }?.take(220),
                speedBps = 0, connections = 0)
        }
    }

    private fun paused() = item.copy(status = Status.PAUSED, speedBps = 0, connections = 0)

    private fun report(progress: Float, line: String) {
        destRe.find(line)?.let {
            val name = File(it.groupValues[1].trim()).name
            if (item.autoName && !intermediateRe.containsMatchIn(name)) item = item.copy(fileName = name)
        }
        var speed = item.speedBps
        speedRe.find(line)?.let {
            val v = it.groupValues[1].toDoubleOrNull() ?: 0.0
            val mult = when (it.groupValues[2]) { "K" -> 1024.0; "M" -> 1024.0 * 1024; "G" -> 1024.0 * 1024 * 1024; else -> 1.0 }
            speed = (v * mult).toLong()
        }
        item = item.copy(percent = if (progress >= 0f) progress else item.percent, speedBps = speed, connections = connections)
        onProgress(item.copy(status = Status.DOWNLOADING))
    }

    private fun isTemp(n: String): Boolean =
        n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".temp") || n.endsWith(".tmp") ||
            n.contains(".part-") || intermediateRe.containsMatchIn(n)
}
