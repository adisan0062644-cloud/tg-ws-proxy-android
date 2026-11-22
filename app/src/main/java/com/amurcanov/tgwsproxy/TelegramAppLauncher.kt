package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast

internal data class TelegramTarget(
    val packageName: String,
    val activityName: String? = null,
)

internal enum class TelegramLaunchPlanType {
    DIRECT_SINGLE,
    CHOOSER_MULTIPLE,
    GENERIC_FALLBACK,
}

internal data class TelegramLaunchPlan(
    val type: TelegramLaunchPlanType,
    val targets: List<TelegramTarget>,
    val singleTarget: TelegramTarget? = null,
)

internal interface TelegramActivityResolver {
    fun queryViewHandlers(): List<TelegramTarget>
    fun resolvePackageHandler(packageName: String): TelegramTarget?
}

internal class PackageManagerTelegramActivityResolver(
    private val packageManager: PackageManager,
    private val uri: Uri,
) : TelegramActivityResolver {
    @Suppress("DEPRECATION")
    override fun queryViewHandlers(): List<TelegramTarget> {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else {
            PackageManager.MATCH_DEFAULT_ONLY
        }
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(flags.toLong()),
                )
            } else {
                packageManager.queryIntentActivities(intent, flags)
            }
        }.getOrDefault(emptyList())
        return resolved.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TelegramTarget(
                packageName = pkg,
                activityName = info.activityInfo?.name?.takeIf { it.isNotBlank() },
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun resolvePackageHandler(packageName: String): TelegramTarget? {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(packageName)
        }
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                )
            } else {
                packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrNull() ?: return null
        val pkg = resolved.activityInfo?.packageName?.takeIf { it.isNotBlank() } ?: return null
        return TelegramTarget(
            packageName = pkg,
            activityName = resolved.activityInfo?.name?.takeIf { it.isNotBlank() },
        )
    }
}

object TelegramAppLauncher {
    val knownTelegramPackages: List<String> = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.beta",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "org.forkclient.messenger",
        "org.forkclient.messenger.beta",
        "com.radolyn.ayugram",
        "app.exteragram.messenger",
        "uz.unnarsx.cherrygram",
        "cc.modery.cherrygram",
        "xyz.nextalone.nagram",
        "io.github.nextalone.nagram",
        "ir.ilmili.telegraph",
        "org.telegram.plus",
        "tw.nekomimi.nekogram",
        "tw.nekomimi.nekogramx",
        "org.telegram.mdgram",
        "com.iMe.android",
        "app.nicegram",
        "org.telegram.bgram",
    )

    internal fun buildLaunchPlan(
        resolver: TelegramActivityResolver,
    ): TelegramLaunchPlan {
        val byPackage = LinkedHashMap<String, TelegramTarget>()
        resolver.queryViewHandlers().forEach { target ->
            if (target.packageName.isNotBlank()) {
                byPackage.putIfAbsent(target.packageName, target)
            }
        }
        for (pkg in knownTelegramPackages) {
            if (pkg in byPackage) continue
            val resolved = resolver.resolvePackageHandler(pkg) ?: continue
            byPackage.putIfAbsent(resolved.packageName, resolved)
        }
        val targets = byPackage.values.toList()
        return when {
            targets.size >= 2 -> TelegramLaunchPlan(
                type = TelegramLaunchPlanType.CHOOSER_MULTIPLE,
                targets = targets,
            )
            targets.size == 1 -> TelegramLaunchPlan(
                type = TelegramLaunchPlanType.DIRECT_SINGLE,
                targets = targets,
                singleTarget = targets.first(),
            )
            else -> TelegramLaunchPlan(
                type = TelegramLaunchPlanType.GENERIC_FALLBACK,
                targets = emptyList(),
            )
        }
    }

    fun openTelegram(context: Context, url: String) {
        val uri = Uri.parse(url)
        val plan = buildLaunchPlan(
            resolver = PackageManagerTelegramActivityResolver(context.packageManager, uri),
        )
        when (plan.type) {
            TelegramLaunchPlanType.DIRECT_SINGLE -> {
                val target = plan.singleTarget
                if (target != null) {
                    val directIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage(target.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val launched = runCatching { context.startActivity(directIntent) }.isSuccess
                    if (launched) return
                }
                launchGenericFallback(context, uri)
            }
            TelegramLaunchPlanType.CHOOSER_MULTIPLE -> {
                val baseIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(
                    baseIntent,
                    context.getString(R.string.choose_telegram_app),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val launched = runCatching { context.startActivity(chooser) }.isSuccess
                if (!launched) {
                    launchGenericFallback(context, uri)
                }
            }
            TelegramLaunchPlanType.GENERIC_FALLBACK -> {
                launchGenericFallback(context, uri)
            }
        }
    }

    private fun launchGenericFallback(context: Context, uri: Uri) {
        try {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.telegram_not_found), Toast.LENGTH_SHORT).show()
        }
    }
}
