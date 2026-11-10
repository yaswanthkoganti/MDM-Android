// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android

import android.app.Application
import app.mdm.android.engine.DownloadManager

class MdmApp : Application() {
    lateinit var manager: DownloadManager
        private set

    override fun onCreate() {
        super.onCreate()
        manager = DownloadManager(this)
    }
}
