// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android.data

import android.content.Context

enum class ProxyKind { NONE, HTTP, SOCKS }

data class AppSettings(
    val maxConnections: Int = 16,       // per file, 1..32 (MDM allows up to 32)
    val maxSimultaneous: Int = 3,       // downloads running at once
    val speedLimitKBps: Int = 0,        // 0 = unlimited
    val wifiOnly: Boolean = false,
    val clipboardOffer: Boolean = true,
    val proxyKind: ProxyKind = ProxyKind.NONE,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
)

class SettingsStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("mdm_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        maxConnections = p.getInt("maxConn", 16),
        maxSimultaneous = p.getInt("maxSim", 3),
        speedLimitKBps = p.getInt("speed", 0),
        wifiOnly = p.getBoolean("wifiOnly", false),
        clipboardOffer = p.getBoolean("clip", true),
        proxyKind = runCatching { ProxyKind.valueOf(p.getString("proxyKind", "NONE") ?: "NONE") }
            .getOrDefault(ProxyKind.NONE),
        proxyHost = p.getString("proxyHost", "") ?: "",
        proxyPort = p.getInt("proxyPort", 0),
    )

    fun save(s: AppSettings) {
        p.edit()
            .putInt("maxConn", s.maxConnections)
            .putInt("maxSim", s.maxSimultaneous)
            .putInt("speed", s.speedLimitKBps)
            .putBoolean("wifiOnly", s.wifiOnly)
            .putBoolean("clip", s.clipboardOffer)
            .putString("proxyKind", s.proxyKind.name)
            .putString("proxyHost", s.proxyHost)
            .putInt("proxyPort", s.proxyPort)
            .apply()
    }
}
