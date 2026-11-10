// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.mdm.android.data.FileCategory
import app.mdm.android.data.Kind
import app.mdm.android.data.categoryFor
import app.mdm.android.ui.MdmRoot
import app.mdm.android.ui.MdmTheme
import app.mdm.android.util.Names
import app.mdm.android.util.URL_REGEX
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val incoming = MutableStateFlow<List<String>>(emptyList())
    private val clipUrl = MutableStateFlow<String?>(null)
    private var lastClip: String? = null
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        val mgr = (application as MdmApp).manager
        setContent {
            MdmTheme {
                MdmRoot(
                    mgr = mgr,
                    incoming = incoming,
                    clip = clipUrl,
                    onIncomingDone = { incoming.value = emptyList() },
                    onClipDone = { clipUrl.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** "Share to Mister DM" and browser hand-off of file links. */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val text = when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> {
                val d = i.data
                if (d != null && (d.scheme == "content" || d.scheme == "file")) {
                    // A .torrent file opened from a file manager or browser
                    val err = (application as MdmApp).manager.addTorrentFile(d)
                    Toast.makeText(this, err ?: "Torrent added", Toast.LENGTH_SHORT).show()
                    return
                }
                i.dataString
            }
            else -> null
        } ?: return
        val urls = URL_REGEX.findAll(text).map { it.value }.distinct().toList()
        if (urls.isNotEmpty()) incoming.value = urls
    }

    /** MDM's "offer to download links copied to the clipboard": Android only lets a focused app read it. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val mgr = (application as MdmApp).manager
        if (!mgr.settings.value.clipboardOffer) return
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        val url = URL_REGEX.find(text)?.value ?: return
        if (url != lastClip &&
            (Names.detectKind(url) != Kind.HTTP || categoryFor(Names.guess(url)) != FileCategory.OTHER)
        ) {
            lastClip = url
            clipUrl.value = url
        }
    }
}
