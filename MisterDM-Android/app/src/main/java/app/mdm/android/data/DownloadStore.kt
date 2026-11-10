// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Crash-safe JSON persistence of the download list (write temp file, then rename). */
class DownloadStore(ctx: Context) {
    private val file = File(ctx.filesDir, "downloads.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): List<DownloadItem> = try {
        if (file.exists()) json.decodeFromString<List<DownloadItem>>(file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun save(items: List<DownloadItem>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(items))
        tmp.renameTo(file)
    }
}
