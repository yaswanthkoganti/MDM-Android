// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android.util

enum class MediaType(val label: String) {
    VIDEO("Video"), AUDIO("Audio"), STREAM("Stream (HLS/DASH)"), FILE("File")
}

/** A media link found on a web page by the in-app browser. */
data class FoundMedia(val url: String, val type: MediaType, val referer: String, val title: String)

object MediaSniffer {
    private val video = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "wmv", "3gp")
    private val audio = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
    private val stream = setOf("m3u8", "mpd")
    private val files = setOf("zip", "rar", "7z", "apk", "pdf", "iso", "exe", "msi")

    /** Returns what kind of media a URL looks like, or null. HLS/DASH segments (.ts, .m4s) are ignored on purpose. */
    fun classify(url: String, includeFiles: Boolean): MediaType? {
        if (!url.startsWith("http", ignoreCase = true)) return null
        val lower = url.lowercase()
        if ("&range=" in lower || "?range=" in lower) return null   // partial chunks of adaptive streams
        val ext = lower.substringBefore('#').substringBefore('?').substringAfterLast('.', "")
        return when {
            ext in stream -> MediaType.STREAM
            ext in video -> MediaType.VIDEO
            ext in audio -> MediaType.AUDIO
            includeFiles && ext in files -> MediaType.FILE
            "mime=video" in lower -> MediaType.VIDEO
            "mime=audio" in lower -> MediaType.AUDIO
            else -> null
        }
    }

    /** JavaScript that reports <video>/<audio>/<source> elements and links to media files back to the app. */
    const val SCAN_JS = """
(function(){
  if (window.__mdmScan) return; window.__mdmScan = 1;
  function scan(){
    var u = [];
    document.querySelectorAll('video,audio,source').forEach(function(e){
      var s = e.currentSrc || e.src; if (s) u.push(s);
    });
    document.querySelectorAll('a[href]').forEach(function(a){
      if (/\.(mp4|webm|mkv|mov|mp3|m4a|flac|m3u8|mpd|zip|rar|7z|apk|pdf)(\?|#|${'$'})/i.test(a.href)) u.push(a.href);
    });
    if (u.length) MdmBridge.report(u.join('\n'));
  }
  scan(); setInterval(scan, 2500);
})();
"""
}
