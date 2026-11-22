package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyStateResetManagerTest {
    @Test
    fun softResetClearsCachesAndCooldownsWhilePreservingMtProtoSecretAndManualDomains() {
        val prefs = InMemorySharedPreferences()

        val manualRepo = SharedPreferencesManualCfDomainRepository(prefs)
        manualRepo.save(listOf("manual.example.org"))

        val cfRepo = CfDomainListRepository(SharedPreferencesCfDomainListPersistence(prefs))
        cfRepo.replaceCache(
            domains = listOf("stale-1.example.com", "stale-2.example.com"),
            fetchedAtMs = 123456L,
            sourceUrl = CfDomainUpdateConfig.PRIMARY_URL,
            sourceType = CfDomainUpdateSourceType.PRIMARY_GITHUB,
            etag = "\"etag-1\"",
            lastModified = "Mon, 01 Jan 2026 00:00:00 GMT",
            primaryStatus = cfRepo.state().primaryStatus,
            mirrorStatus = cfRepo.state().mirrorStatus,
        )

        val savedStore = ProxySavedConfigStore(prefs)
        savedStore.save(
            ProxySavedConfig(
                port = 1443,
                runtimeConfig = "@cf_cached_domains=stale-1.example.com",
                poolSize = 4,
                frontendType = LocalProxyFrontendType.MTPROTO_EXPERIMENTAL,
            ),
        )

        val statsRepo = AdaptiveRouteStatsRepository(prefs)
        statsRepo.saveEncodedStats("lg:profile1:2:0:cf_proxy_ws:999999")

        val mtProtoRepo = MtProtoProxyConfigRepository(prefs)
        val initialSecret = "0123456789abcdef0123456789abcdef"
        mtProtoRepo.save(
            MtProtoProxyConfig(
                host = "127.0.0.1",
                port = 1443,
                secret = initialSecret,
                enabled = true,
                experimentalAcknowledged = true,
            ),
        )

        var nativeResetCalled = false
        var reconnectCalled = false
        val result = ProxyStateResetManager.resetConnectionState(
            context = null,
            prefs = prefs,
            regenerateSecret = false,
            reconnectIfRunning = true,
            runtimeRunning = true,
            nativeReset = { nativeResetCalled = true },
            reconnectLauncher = { reconnectCalled = true },
        )

        assertTrue(nativeResetCalled)
        assertTrue(reconnectCalled)
        assertFalse(result.regeneratedSecret)
        assertEquals(initialSecret, result.mtProtoConfig.secret)
        assertEquals(initialSecret, mtProtoRepo.load().secret)
        assertTrue(result.upstreamState.domains.isEmpty())
        assertTrue(cfRepo.state().domains.isEmpty())
        assertEquals("", savedStore.load().runtimeConfig)
        assertEquals("", statsRepo.loadEncodedStats())
        assertEquals(listOf("manual.example.org"), manualRepo.load())
    }

    @Test
    fun fullResetRegeneratesMtProtoSecretWhenRequested() {
        val prefs = InMemorySharedPreferences()

        val mtProtoRepo = MtProtoProxyConfigRepository(prefs)
        val initialSecret = "0123456789abcdef0123456789abcdef"
        mtProtoRepo.save(
            MtProtoProxyConfig(
                host = "127.0.0.1",
                port = 1443,
                secret = initialSecret,
                enabled = true,
                experimentalAcknowledged = true,
            ),
        )

        val result = ProxyStateResetManager.resetConnectionState(
            context = null,
            prefs = prefs,
            regenerateSecret = true,
            reconnectIfRunning = false,
            runtimeRunning = false,
            nativeReset = {},
        )

        assertTrue(result.regeneratedSecret)
        assertNotEquals(initialSecret, result.mtProtoConfig.secret)
        assertEquals(32, result.mtProtoConfig.secret.length)
        assertEquals(result.mtProtoConfig.secret, mtProtoRepo.load().secret)
    }
}
