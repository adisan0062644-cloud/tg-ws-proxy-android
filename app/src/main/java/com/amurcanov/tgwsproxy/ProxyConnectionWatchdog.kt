package com.amurcanov.tgwsproxy

internal enum class WatchdogAction {
    NONE,
    SOFT_HEAL,
    DEEP_HEAL,
}

internal data class WatchdogEvaluation(
    val action: WatchdogAction,
    val reason: String = "",
)

/**
 * Monitors periodic runtime metrics to detect when Telegram is stuck retrying connections
 * without exchanging any bytes (e.g. poisoned CF domain cooldowns or stale upstream cache),
 * and escalates from a zero-downtime cooldown reset to a full cache clear + reconnect.
 */
internal class ProxyConnectionWatchdog(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var initialized = false
    private var lastTotalBytes = 0L
    private var lastTotalConnections = 0L
    private var lastCfPoolMisses = 0
    private var lastCfPoolErrors = 0

    private var stallStartedAtMs: Long? = null
    private var stallReason: String = ""
    private var softHealAttemptedInStall = false
    private var lastSoftHealAtMs = 0L
    private var lastDeepHealAtMs = 0L

    fun reset() {
        initialized = false
        lastTotalBytes = 0L
        lastTotalConnections = 0L
        lastCfPoolMisses = 0
        lastCfPoolErrors = 0
        stallStartedAtMs = null
        stallReason = ""
        softHealAttemptedInStall = false
    }

    fun onSample(metrics: ProxyRuntimeMetrics): WatchdogEvaluation {
        if (!metrics.running) {
            reset()
            return WatchdogEvaluation(WatchdogAction.NONE)
        }

        val now = nowMs()
        val totalBytes = metrics.bytesUp + metrics.bytesDown
        if (!initialized) {
            initialized = true
            lastTotalBytes = totalBytes
            lastTotalConnections = metrics.totalConnections
            lastCfPoolMisses = metrics.cfPoolMisses
            lastCfPoolErrors = metrics.cfPoolErrors
            return WatchdogEvaluation(WatchdogAction.NONE)
        }

        if (totalBytes > lastTotalBytes) {
            lastTotalBytes = totalBytes
            lastTotalConnections = metrics.totalConnections
            lastCfPoolMisses = metrics.cfPoolMisses
            lastCfPoolErrors = metrics.cfPoolErrors
            stallStartedAtMs = null
            stallReason = ""
            softHealAttemptedInStall = false
            return WatchdogEvaluation(WatchdogAction.NONE)
        }

        val connectionsGrowing = metrics.totalConnections > lastTotalConnections
        val cfMissesGrowing = metrics.cfPoolMisses > lastCfPoolMisses ||
            metrics.cfPoolErrors > lastCfPoolErrors
        val errorSignal = isRecoverableError(metrics.lastError)
        val hasConnectionActivity = metrics.totalConnections > 0L ||
            metrics.activeConnections > 0L ||
            metrics.cfPoolMisses > 0 ||
            metrics.cfPoolErrors > 0

        val stalledNow = hasConnectionActivity && (
            cfMissesGrowing ||
                (connectionsGrowing && metrics.currentRouteKind.isBlank()) ||
                (connectionsGrowing && errorSignal) ||
                (metrics.activeConnections > 0L && errorSignal) ||
                (errorSignal && metrics.currentRouteKind.isBlank())
            )

        lastTotalConnections = metrics.totalConnections
        lastCfPoolMisses = metrics.cfPoolMisses
        lastCfPoolErrors = metrics.cfPoolErrors

        if (!stalledNow) {
            // Keep existing stall timer alive briefly if an error persists with 0 active sockets
            // while client waits before next reconnect; reset only if proxy is genuinely clean/idle.
            if (!errorSignal && metrics.activeConnections == 0L) {
                stallStartedAtMs = null
                stallReason = ""
                softHealAttemptedInStall = false
            }
            return WatchdogEvaluation(WatchdogAction.NONE)
        }

        if (stallStartedAtMs == null) {
            stallStartedAtMs = now
            stallReason = deriveReason(metrics, cfMissesGrowing, connectionsGrowing)
        } else if (stallReason.isBlank() || metrics.lastError.isNotBlank()) {
            stallReason = deriveReason(metrics, cfMissesGrowing, connectionsGrowing)
        }

        val elapsedStallMs = now - (stallStartedAtMs ?: now)
        if (!softHealAttemptedInStall) {
            if (elapsedStallMs >= SOFT_HEAL_DELAY_MS &&
                (lastSoftHealAtMs == 0L || now - lastSoftHealAtMs >= SOFT_HEAL_COOLDOWN_MS)
            ) {
                softHealAttemptedInStall = true
                lastSoftHealAtMs = now
                return WatchdogEvaluation(WatchdogAction.SOFT_HEAL, stallReason)
            }
            return WatchdogEvaluation(WatchdogAction.NONE)
        }

        if (elapsedStallMs >= DEEP_HEAL_DELAY_MS &&
            now - lastSoftHealAtMs >= STAGE_GAP_MS &&
            (lastDeepHealAtMs == 0L || now - lastDeepHealAtMs >= DEEP_HEAL_COOLDOWN_MS)
        ) {
            lastDeepHealAtMs = now
            stallStartedAtMs = null
            val reason = stallReason
            stallReason = ""
            softHealAttemptedInStall = false
            return WatchdogEvaluation(WatchdogAction.DEEP_HEAL, reason)
        }

        return WatchdogEvaluation(WatchdogAction.NONE)
    }

    private fun deriveReason(
        metrics: ProxyRuntimeMetrics,
        cfMissesGrowing: Boolean,
        connectionsGrowing: Boolean,
    ): String {
        val err = metrics.lastError.trim()
        return when {
            err.isNotBlank() -> err
            cfMissesGrowing -> "cf_pool_exhausted"
            connectionsGrowing -> "connect_stalled_no_bytes"
            else -> "stalled_no_traffic"
        }
    }

    companion object {
        const val SOFT_HEAL_DELAY_MS = 6_000L
        const val DEEP_HEAL_DELAY_MS = 18_000L
        const val STAGE_GAP_MS = 8_000L
        const val SOFT_HEAL_COOLDOWN_MS = 20_000L
        const val DEEP_HEAL_COOLDOWN_MS = 60_000L

        internal fun isRecoverableError(lastError: String): Boolean {
            if (lastError.isBlank()) return false
            val lower = lastError.lowercase()
            return lower.contains("cfproxy") ||
                lower.contains("timeout") ||
                lower.contains("deadline") ||
                lower.contains("unavailable") ||
                lower.contains("cooldown") ||
                lower.contains("refused") ||
                lower.contains("reset") ||
                lower.contains("eof") ||
                lower.contains("http_4") ||
                lower.contains("http_5") ||
                lower.contains("websocket") ||
                lower.contains("connect_failed")
        }
    }
}
