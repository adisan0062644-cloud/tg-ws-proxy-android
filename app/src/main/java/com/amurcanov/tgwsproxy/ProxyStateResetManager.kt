package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

internal data class ProxyStateResetResult(
    val regeneratedSecret: Boolean,
    val upstreamState: CfDomainUpstreamState,
    val mtProtoConfig: MtProtoProxyConfig,
    val reconnectRequested: Boolean,
)

internal object ProxyStateResetManager {
    fun resetConnectionState(
        context: Context?,
        prefs: SharedPreferences,
        regenerateSecret: Boolean = false,
        reconnectIfRunning: Boolean = true,
        runtimeRunning: Boolean = ProxyService.isRunning.value,
        nativeReset: () -> Unit = {
            NativeProxy.resetConnectionState()
            NativeProxy.resetAdaptiveRouteStatsSafe(true)
        },
        reconnectLauncher: ((Context?) -> Unit)? = null,
    ): ProxyStateResetResult {
        val cfRepo = CfDomainListRepository(SharedPreferencesCfDomainListPersistence(prefs))
        val clearedUpstream = cfRepo.clearCache()

        val savedConfigStore = ProxySavedConfigStore(prefs)
        val savedConfig = savedConfigStore.load()
        savedConfigStore.save(savedConfig.copy(runtimeConfig = ""))

        AdaptiveRouteStatsRepository(prefs).resetAll()
        CfDomainDiagnosticsState.resetCooldowns()
        runCatching { nativeReset() }

        val mtProtoRepo = MtProtoProxyConfigRepository(prefs)
        val currentMtProto = mtProtoRepo.load()
        val updatedMtProto = if (regenerateSecret) {
            mtProtoRepo.save(
                currentMtProto.copy(secret = MtProtoSecretGenerator.generate()),
            )
        } else {
            currentMtProto
        }

        if (context != null) {
            AppLogger.i(
                context = context,
                category = AppLogCategory.APP,
                message = "PROXY_STATE_RESET",
                details = mapOf(
                    "regenerateSecret" to regenerateSecret.toString(),
                    "wasRunning" to runtimeRunning.toString(),
                ),
            )
        }

        var reconnectRequested = false
        if (reconnectIfRunning && runtimeRunning) {
            reconnectRequested = runCatching {
                val launch = reconnectLauncher ?: { ctx: Context? ->
                    if (ctx != null) {
                        val intent = Intent(ctx, ProxyService::class.java).apply {
                            action = ProxyService.ACTION_RECONNECT
                        }
                        ctx.startService(intent)
                    }
                }
                launch(context)
            }.isSuccess
        }

        return ProxyStateResetResult(
            regeneratedSecret = regenerateSecret,
            upstreamState = clearedUpstream,
            mtProtoConfig = updatedMtProto,
            reconnectRequested = reconnectRequested,
        )
    }
}
