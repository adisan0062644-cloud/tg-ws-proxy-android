package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyConnectionWatchdogTest {
    @Test
    fun idleProxyWithNoConnectionsNeverTriggersHealing() {
        var now = 1_000L
        val watchdog = ProxyConnectionWatchdog { now }

        val baseline = ProxyRuntimeMetrics(running = true, mode = "mtproto")
        assertEquals(WatchdogAction.NONE, watchdog.onSample(baseline).action)

        now += 30_000L
        assertEquals(WatchdogAction.NONE, watchdog.onSample(baseline).action)
    }

    @Test
    fun stalledConnectionsWithCfPoolErrorEscalateFromSoftHealToDeepHeal() {
        var now = 1_000L
        val watchdog = ProxyConnectionWatchdog { now }

        assertEquals(
            WatchdogAction.NONE,
            watchdog.onSample(
                ProxyRuntimeMetrics(
                    running = true,
                    mode = "mtproto",
                    totalConnections = 0,
                    bytesUp = 0,
                    bytesDown = 0,
                ),
            ).action,
        )

        now += 1_000L
        assertEquals(
            WatchdogAction.NONE,
            watchdog.onSample(
                ProxyRuntimeMetrics(
                    running = true,
                    mode = "mtproto",
                    totalConnections = 3,
                    activeConnections = 2,
                    bytesUp = 0,
                    bytesDown = 0,
                    lastError = "cfproxy_unavailable",
                ),
            ).action,
        )

        // Advance past SOFT_HEAL_DELAY_MS while error/zero bytes persist
        now += ProxyConnectionWatchdog.SOFT_HEAL_DELAY_MS
        val soft = watchdog.onSample(
            ProxyRuntimeMetrics(
                running = true,
                mode = "mtproto",
                totalConnections = 6,
                activeConnections = 2,
                bytesUp = 0,
                bytesDown = 0,
                lastError = "cfproxy_unavailable",
            ),
        )
        assertEquals(WatchdogAction.SOFT_HEAL, soft.action)

        // Advance past DEEP_HEAL_DELAY_MS with still 0 bytes exchanged
        now += ProxyConnectionWatchdog.DEEP_HEAL_DELAY_MS
        val deep = watchdog.onSample(
            ProxyRuntimeMetrics(
                running = true,
                mode = "mtproto",
                totalConnections = 9,
                activeConnections = 1,
                bytesUp = 0,
                bytesDown = 0,
                lastError = "cfproxy_unavailable",
            ),
        )
        assertEquals(WatchdogAction.DEEP_HEAL, deep.action)
    }

    @Test
    fun flowingTrafficResetsStallTimerImmediately() {
        var now = 1_000L
        val watchdog = ProxyConnectionWatchdog { now }

        watchdog.onSample(ProxyRuntimeMetrics(running = true, mode = "mtproto"))

        now += 1_000L
        watchdog.onSample(
            ProxyRuntimeMetrics(
                running = true,
                mode = "mtproto",
                totalConnections = 2,
                bytesUp = 0,
                bytesDown = 0,
                lastError = "cfproxy_all_domains_failed",
            ),
        )

        // Bytes start flowing cleanly before stall threshold
        now += 2_000L
        assertEquals(
            WatchdogAction.NONE,
            watchdog.onSample(
                ProxyRuntimeMetrics(
                    running = true,
                    mode = "mtproto",
                    activeRouteKind = "cf_proxy_ws",
                    totalConnections = 2,
                    bytesUp = 512,
                    bytesDown = 1024,
                    lastError = "",
                ),
            ).action,
        )

        // Idle after healthy transfer should not heal
        now += 30_000L
        assertEquals(
            WatchdogAction.NONE,
            watchdog.onSample(
                ProxyRuntimeMetrics(
                    running = true,
                    mode = "mtproto",
                    activeRouteKind = "cf_proxy_ws",
                    totalConnections = 2,
                    bytesUp = 512,
                    bytesDown = 1024,
                    lastError = "",
                ),
            ).action,
        )
    }
}
