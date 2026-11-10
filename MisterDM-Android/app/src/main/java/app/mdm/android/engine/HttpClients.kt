// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android.engine

import app.mdm.android.data.AppSettings
import app.mdm.android.data.ProxyKind
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object HttpClients {
    fun build(s: AppSettings): OkHttpClient {
        val b = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // HTTP/2 would multiplex every segment over ONE TCP connection, defeating multi-connection
            // acceleration. HTTP/1.1 gives each segment its own connection (and its own congestion window).
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(64, 5, TimeUnit.MINUTES))
        if (s.proxyKind != ProxyKind.NONE && s.proxyHost.isNotBlank() && s.proxyPort in 1..65535) {
            val type = if (s.proxyKind == ProxyKind.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
            b.proxy(Proxy(type, InetSocketAddress.createUnresolved(s.proxyHost, s.proxyPort)))
        }
        return b.build()
    }
}
