// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android.data

import kotlinx.serialization.Serializable

@Serializable
enum class Status { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

@Serializable
enum class Kind { HTTP, VIDEO, TORRENT }

enum class FileCategory(val label: String) {
    COMPRESSED("Compressed"), DOCUMENTS("Documents"), MUSIC("Music"),
    VIDEO("Video"), PROGRAMS("Programs"), IMAGES("Images"), OTHER("Other")
}

fun categoryFor(fileName: String): FileCategory {
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "zst" -> FileCategory.COMPRESSED
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "odt", "csv" -> FileCategory.DOCUMENTS
        "mp3", "flac", "wav", "aac", "ogg", "m4a", "opus" -> FileCategory.MUSIC
        "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts" -> FileCategory.VIDEO
        "apk", "exe", "msi", "dmg", "deb", "rpm", "appimage", "iso" -> FileCategory.PROGRAMS
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic" -> FileCategory.IMAGES
        else -> FileCategory.OTHER
    }
}

/** Persisted state of one connection's byte range, so a download can resume exactly. */
@Serializable
data class SegState(val start: Long, val end: Long, val pos: Long)

@Serializable
data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    val autoName: Boolean = true,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: Status = Status.QUEUED,
    val error: String? = null,
    val speedBps: Long = 0,
    val connections: Int = 0,
    val acceptsRanges: Boolean = false,
    val segments: List<SegState> = emptyList(),
    val username: String? = null,
    val password: String? = null,
    val sha256: String? = null,
    val checksumOk: Boolean? = null,
    val contentUri: String? = null,
    val mime: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val kind: Kind = Kind.HTTP,
    val audioOnly: Boolean = false,
    val videoHeight: Int = 0,          // 0 = best available
    val percent: Float = -1f,          // used by video downloads (yt-dlp reports a percentage, not bytes)
    val files: List<String> = emptyList(),   // finished output files, relative to the work dir (torrent/video)
    val referer: String? = null,       // set when the link was grabbed from a web page (in-app browser)
    val cookie: String? = null,
    val userAgent: String? = null,
) {
    val category: FileCategory get() = categoryFor(fileName)
    val progress: Float
        get() = when {
            percent >= 0f -> (percent / 100f).coerceIn(0f, 1f)
            totalBytes > 0 -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> 0f
        }
}
