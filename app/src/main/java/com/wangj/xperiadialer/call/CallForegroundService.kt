package com.wangj.xperiadialer.call

import android.app.Notification
import android.app.Service
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * 通话前台服务。
 *
 * Android 14+ 必须声明 android:foregroundServiceType="phoneCall" 并持有
 * FOREGROUND_SERVICE_PHONE_CALL；运行时还需要满足「声明 MANAGE_OWN_CALLS」或
 * 「持有 ROLE_DIALER」二者之一，否则 startForeground 会抛 SecurityException。
 *
 * ## 这个服务必须"脆弱隔离"（v1.9.2）
 *
 * `startForeground()` 失败的后果不是抛个异常那么简单：
 * - 通知被判为「坏」→ 系统直接杀进程
 *   （`CannotPostForegroundServiceNotificationException`）；
 * - 干脆没进前台 → 5 秒后系统再杀一次
 *   （`ForegroundServiceDidNotStartInTimeException`，真机上已经踩过）。
 *
 * 所以这里做了三层：入口先检查有没有资格当前台服务 → 主通知失败退兜底通知 →
 * 都失败才放弃。只要 `startForeground()` 成功一次，系统就不会再拿 5 秒规则杀我们。
 */
class CallForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通道创建是幂等的；冷启动由本服务拉起进程时也保证通道已存在
        runCatching { CallNotificationManager.createChannels(this) }

        val primary = runCatching { CallNotificationManager.buildOngoing(this) }
            .onFailure { Log.e(TAG, "build notification failed", it) }
            .getOrElse { CallNotificationManager.buildMinimal(this) }

        val ok = startForegroundSafely(primary) ||
            run {
                Log.w(TAG, "primary notification rejected, falling back to minimal")
                startForegroundSafely(CallNotificationManager.buildMinimal(this))
            }

        if (ok) {
            running = true
            starting = false
        } else {
            Log.e(TAG, "startForeground failed twice, stop service")
            starting = false
            stopSelf()
        }
        /*
         * 不要 START_STICKY：进程被杀后系统会用 null intent 重启服务，
         * 那时早就没有通话了，重建一条"未知号码·通话中"的通知没有意义，
         * 还多一次可能失败的 startForeground。需要时由 update() 显式拉起。
         */
        return START_NOT_STICKY
    }

    private fun startForegroundSafely(notification: Notification): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                CallNotificationManager.NOTIF_CALL,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(CallNotificationManager.NOTIF_CALL, notification)
        }
    }.onSuccess {
        Log.i(TAG, "startForeground ok")
    }.onFailure {
        Log.e(TAG, "startForeground failed", it)
    }.isSuccess

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        starting = false
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallForegroundService"

        /** 本进程内是否已经成功进入过前台（进程重启会归零） */
        @Volatile
        private var running = false

        /**
         * 是否已经发出过 `startForegroundService`、但服务实例还没跑起来。
         *
         * 这个标志解决的是一个真实竞态：`update()` 被连续调用两次（实测
         * `syncNotification` 与 `onCallAdded` 相隔 5ms），而 `startForeground`
         * 要等服务 `onCreate` + `onStartCommand` 之后才执行（实测约 100ms）。
         * 这段时间里 `running` 还是 false，两次调用会各发一次启动请求，
         * 系统于是送来两次 `onStartCommand`，`startForeground` 被打两遍。
         *
         * 两次内容相同的通知这次没被判「坏」，但只要两次内容有差异
         * （比如一次 primary 一次 minimal），就会重演 v1.6.1 的
         * `CannotPostForegroundServiceNotificationException` → 进程被杀。
         */
        @Volatile
        private var starting = false

        /**
         * 刷新通话通知 / 确保服务在前台。
         *
         * **已经在前台时只更新通知，不再调用 startForeground。**
         *
         * 每次状态变化都重新 startForeground 的代价太大：那条路径一旦被系统判定
         * 通知不合法，不是抛异常而是直接杀进程（`killMisbehavingService`），
         * 通话界面跟着一起没。所以整个服务的生命周期里只 startForeground 一次
         * （就是来电那一刻，此时用的是最简单的纯文本通知，不带 CallStyle），
         * 后续所有内容变化都走 NotificationManager.notify —— 失败也只是通知不更新，
         * 由 runCatching 兜住，不会伤到进程。
         */
        fun update(context: Context, reason: String) {
            val calls = CallManager.uiState.value.calls.size
            Log.i(TAG, "update reason=$reason running=$running starting=$starting calls=$calls")
            if (running) {
                runCatching {
                    NotificationManagerCompat.from(context)
                        .notify(CallNotificationManager.NOTIF_CALL, CallNotificationManager.buildOngoing(context))
                }.onFailure { Log.w(TAG, "update notification failed", it) }
                return
            }
            if (starting) {
                // 已经发过启动请求，服务马上会 onCreate + startForeground。
                // 这里再发一次只会换来第二次 onStartCommand 和第二次 startForeground。
                Log.i(TAG, "update skipped, start already requested")
                return
            }
            if (!canRun(context)) return
            starting = true
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(Intent(context, CallForegroundService::class.java))
                } else {
                    context.startService(Intent(context, CallForegroundService::class.java))
                }
            }.onFailure { Log.w(TAG, "start service failed", it) }.isSuccess
            if (!ok) starting = false
        }

        fun stop(context: Context) {
            running = false
            starting = false
            runCatching { context.stopService(Intent(context, CallForegroundService::class.java)) }
                .onFailure { Log.w(TAG, "stop service failed", it) }
        }

        /**
         * 有没有资格以前台服务形式运行。
         *
         * 没资格还硬起，只会换来一次 `SecurityException`
         * （索尼拨号器丢了默认拨号器角色后就是这么连环崩的），
         * 而通话界面本身靠 Telecom 绑定 InCallService 就能活着，不缺这一条通知。
         */
        private fun canRun(context: Context): Boolean {
            if (!CallNotificationManager.hasNotificationPermission(context)) {
                Log.w(TAG, "no notification permission, skip foreground service")
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                val held = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
                if (!held) {
                    Log.w(TAG, "dialer role not held, skip foreground service")
                    return false
                }
            }
            return true
        }
    }
}
