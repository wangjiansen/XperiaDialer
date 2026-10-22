package com.wangj.xperiadialer

import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log

/**
 * 角色 / 权限状态查询与跳转。
 *
 * 注意 [TelecomManager.getDefaultDialerPackage] 是判断「是否为默认电话应用」
 * 最可靠的方式，不依赖 RoleManager 的版本差异。
 *
 * 角色申请不能只走 [RoleManager.createRequestRoleIntent] 一条路：
 * 系统侧一旦判定本 App 不满足角色资格，会直接 RESULT_CANCELED 且不给任何提示，
 * 表现为「点按钮没反应」。因此这里提供分级 fallback + 自检项。
 */
object RoleHelper {

    private const val TAG = "RoleHelper"
    private const val ACTION_IN_CALL_SERVICE = "android.telecom.InCallService"

    // ---------- 状态查询 ----------

    fun isDefaultDialer(context: Context): Boolean =
        context.packageName == currentDialerPackage(context)

    fun currentDialerPackage(context: Context): String? =
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage

    fun isCallScreeningApp(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) }.getOrDefault(false)
    }

    /** Android 14+ 起 USE_FULL_SCREEN_INTENT 变成需要用户单独授权的特殊权限 */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.canUseFullScreenIntent()
    }

    // ---------- 角色资格自检 ----------

    fun isRoleAvailable(context: Context, role: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { rm.isRoleAvailable(role) }.getOrDefault(false)
    }

    /** ROLE_DIALER 硬条件之一：系统必须能解析到本 App 声明的 InCallService */
    fun inCallServiceResolvable(context: Context): Boolean {
        val list = runCatching {
            queryServices(context, Intent(ACTION_IN_CALL_SERVICE))
        }.getOrDefault(emptyList())
        return list.any { it == context.packageName }
    }

    /** ROLE_DIALER 硬条件之二：系统必须能解析到本 App 的 ACTION_DIAL（拨号盘 UI） */
    fun dialActivityResolvable(context: Context): Boolean {
        val list = runCatching {
            queryActivities(context, Intent(Intent.ACTION_DIAL))
        }.getOrDefault(emptyList())
        return list.any { it == context.packageName }
    }

    /**
     * ROLE_DIALER 在 roles.xml 里要求「两个」拨号入口：
     *   1. ACTION_DIAL（无 data）
     *   2. ACTION_DIAL + data scheme="tel"
     * 两条都命中才算合格。对应的 intent-filter 都必须带 CATEGORY_DEFAULT。
     */
    fun dialTelResolvable(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:10086"))
        val list = runCatching { queryActivities(context, intent) }.getOrDefault(emptyList())
        return list.any { it == context.packageName }
    }

    /** 系统视角下所有能处理 ACTION_DIAL 的包（默认电话应用候选的来源） */
    fun visibleDialerPackages(context: Context): List<String> =
        runCatching { queryActivities(context, Intent(Intent.ACTION_DIAL)) }
            .getOrDefault(emptyList())
            .distinct()
            .sorted()

    /** 系统视角下所有声明了 InCallService 的包 */
    fun visibleInCallServicePackages(context: Context): List<String> =
        runCatching { queryServices(context, Intent(ACTION_IN_CALL_SERVICE)) }
            .getOrDefault(emptyList())
            .distinct()
            .sorted()

    // ---------- 跳转 ----------

    /**
     * 优先走 RoleManager 的角色申请弹窗。
     * 不可用 / 无法解析时返回 null，调用方必须回退到 [buildSettingsFallback]。
     */
    fun buildRequestRoleIntent(context: Context, role: String): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val rm = context.getSystemService(RoleManager::class.java) ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !runCatching { rm.isRoleAvailable(role) }.getOrDefault(false)
        ) {
            Log.w(TAG, "role unavailable on this device: $role")
            return null
        }

        val intent = runCatching { rm.createRequestRoleIntent(role) }.getOrNull()
        if (intent == null) {
            Log.w(TAG, "createRequestRoleIntent returned null: $role")
            return null
        }
        // 注意：这里不能用 resolvable() 拦截。
        // PermissionController 在 Android 11+ 的包可见性规则下可能查不到，
        // 会被误判成「不可用」而跳过系统弹窗。交给 launch 时的异常来兜底。
        if (!resolvable(context, intent)) {
            Log.w(TAG, "role request intent not resolvable (may be package-visibility false negative): $role")
        }
        return intent
    }

    /** 兜底：系统「默认应用」设置页 → 本 App 详情页 */
    fun buildSettingsFallback(context: Context): Intent? {
        val candidates = buildList {
            add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            add(Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"))
            add(Intent(Settings.ACTION_SETTINGS))
            add(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
        return candidates.firstOrNull { resolvable(context, it) }.also {
            Log.i(TAG, "settings fallback -> $it")
        }
    }

    /** 锁屏全屏来电授权页 */
    fun buildFullScreenIntentSettings(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${context.packageName}")
        )
        return if (resolvable(context, intent)) intent
        else buildSettingsFallback(context)
    }

    /** 安全启动：吞掉 ActivityNotFoundException，返回是否成功 */
    fun safeStart(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.onFailure { Log.e(TAG, "startActivity failed: $intent", it) }.getOrDefault(false)
    }

    // ---------- 内部工具 ----------

    private fun resolvable(context: Context, intent: Intent): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(
                    intent, PackageManager.ResolveInfoFlags.of(0)
                ) != null
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveActivity(intent, 0) != null
            }
        }.getOrDefault(false)

    private fun queryServices(context: Context, intent: Intent): List<String> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }.mapNotNull { it?.serviceInfo?.packageName }
    }

    private fun queryActivities(context: Context, intent: Intent): List<String> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }.mapNotNull { it?.activityInfo?.packageName }
    }
}
