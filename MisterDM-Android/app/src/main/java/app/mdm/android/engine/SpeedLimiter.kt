// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Global bytes-per-second limiter shared by every connection of every download. */
class SpeedLimiter {
    @Volatile var limitBps: Long = 0
    private val mutex = Mutex()
    private var windowStart = System.nanoTime()
    private var used = 0L

    suspend fun acquire(n: Int) {
        while (true) {
            val limit = limitBps
            if (limit <= 0) return
            val waitMs = mutex.withLock {
                val now = System.nanoTime()
                if (now - windowStart >= 1_000_000_000L) { windowStart = now; used = 0 }
                if (used < limit) { used += n; 0L }
                else (windowStart + 1_000_000_000L - now) / 1_000_000L + 1
            }
            if (waitMs == 0L) return
            delay(waitMs)
        }
    }
}
