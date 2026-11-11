// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android.util

import android.net.Uri
import app.mdm.android.data.Kind
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

val URL_REGEX = Regex("(?:https?://|magnet:\\?)[^\\s\"'<>]+")

object Fmt {
    fun bytes(n: Long): String {
        if (n < 0) return "?"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "$n B" else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun speed(bps: Long): String = bytes(bps) + "/s"

    fun eta(seconds: Long): String {
        if (seconds < 0) return "--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}

object Hashing {
    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

object Names {
    private val bad = Regex("[\\\\/:*?\"<>|]")

    fun sanitize(name: String): String = name.replace(bad, "_").trim().ifEmpty { "download" }

    fun guess(url: String): String {
        val seg = Uri.parse(url).lastPathSegment
        return sanitize(if (seg.isNullOrBlank()) "download" else seg)
    }

    fun fromContentDisposition(h: String?): String? {
        if (h == null) return null
        Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE).find(h)?.let {
            return sanitize(runCatching { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }
                .getOrDefault(it.groupValues[1].trim()))
        }
        Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(h)?.let {
            return sanitize(it.groupValues[1].trim())
        }
        return null
    }

    private val videoHosts = listOf(
        "youtube.com", "youtu.be", "vimeo.com", "dailymotion.com", "twitch.tv", "tiktok.com",
        "facebook.com", "fb.watch", "instagram.com", "twitter.com", "x.com", "reddit.com",
        "soundcloud.com", "bilibili.com", "streamable.com", "rumble.com",
    )

    fun isTorrentLike(url: String): Boolean {
        if (url.startsWith("magnet:", ignoreCase = true)) return true
        return url.substringBefore('?').lowercase().endsWith(".torrent")
    }

    fun detectKind(url: String): Kind {
        if (isTorrentLike(url)) return Kind.TORRENT
        val path = url.substringBefore('?').lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return Kind.VIDEO
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return Kind.HTTP
        return if (videoHosts.any { host == it || host.endsWith(".$it") }) Kind.VIDEO else Kind.HTTP
    }

    fun magnetName(url: String): String? {
        val m = Regex("[?&]dn=([^&]+)").find(url) ?: return null
        return runCatching { sanitize(URLDecoder.decode(m.groupValues[1], "UTF-8")) }.getOrNull()
    }
}
