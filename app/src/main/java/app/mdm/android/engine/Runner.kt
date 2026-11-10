// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android.engine

import app.mdm.android.data.DownloadItem

/** One running transfer (HTTP file, torrent or video). run() always returns a final item state. */
interface Runner {
    suspend fun run(): DownloadItem
    fun stop()
}
