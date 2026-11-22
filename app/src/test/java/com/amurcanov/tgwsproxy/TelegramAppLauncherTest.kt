package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramAppLauncherTest {
    private class FakeResolver(
        private val queried: List<TelegramTarget>,
        private val perPackage: Map<String, TelegramTarget> = emptyMap(),
    ) : TelegramActivityResolver {
        override fun queryViewHandlers(): List<TelegramTarget> = queried
        override fun resolvePackageHandler(packageName: String): TelegramTarget? =
            perPackage[packageName]
    }

    @Test
    fun singleInstalledAppOpensDirectly() {
        val plan = TelegramAppLauncher.buildLaunchPlan(
            resolver = FakeResolver(
                queried = listOf(TelegramTarget("org.telegram.messenger", "LaunchActivity")),
            ),
        )

        assertEquals(TelegramLaunchPlanType.DIRECT_SINGLE, plan.type)
        assertEquals("org.telegram.messenger", plan.singleTarget?.packageName)
    }

    @Test
    fun duplicateAliasesInsideSinglePackageStillOpenDirectly() {
        val plan = TelegramAppLauncher.buildLaunchPlan(
            resolver = FakeResolver(
                queried = listOf(
                    TelegramTarget("org.telegram.messenger", "LaunchActivity"),
                    TelegramTarget("org.telegram.messenger", "ProxyActivity"),
                ),
            ),
        )

        assertEquals(TelegramLaunchPlanType.DIRECT_SINGLE, plan.type)
        assertEquals(1, plan.targets.size)
    }

    @Test
    fun multipleInstalledTelegramClientsTriggerChooserIncludingForks() {
        val plan = TelegramAppLauncher.buildLaunchPlan(
            resolver = FakeResolver(
                queried = listOf(
                    TelegramTarget("org.telegram.messenger"),
                    TelegramTarget("org.forkclient.messenger"),
                ),
                perPackage = mapOf(
                    "uz.unnarsx.cherrygram" to TelegramTarget("uz.unnarsx.cherrygram"),
                ),
            ),
        )

        assertEquals(TelegramLaunchPlanType.CHOOSER_MULTIPLE, plan.type)
        assertEquals(
            listOf(
                "org.telegram.messenger",
                "org.forkclient.messenger",
                "uz.unnarsx.cherrygram",
            ),
            plan.targets.map { it.packageName },
        )
    }

    @Test
    fun noHandlersUsesGenericFallback() {
        val plan = TelegramAppLauncher.buildLaunchPlan(
            resolver = FakeResolver(queried = emptyList()),
        )

        assertEquals(TelegramLaunchPlanType.GENERIC_FALLBACK, plan.type)
        assertTrue(plan.targets.isEmpty())
    }
}
