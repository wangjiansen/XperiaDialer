package com.wangj.xperiadialer.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.wangj.xperiadialer.R

/**
 * 来电 / 通话通知。
 *
 * ## 为什么拆成两条通知（v1.9.2 重构）
 *
 * 之前只有一条 `NOTIF_CALL`，既是**前台服务的常驻通知**，又要承担**来电提醒**
 * （高优先级 + fullScreenIntent）。为了「界面在前台时别用 heads-up 盖住界面」，
 * 界面可见时就换到低优先级通道重新 startForeground。
 *
 * 这个做法在真机上炸了：
 * ```
 * CannotPostForegroundServiceNotificationException: Bad notification for startForeground
 * ```
 * 系统认为这条通知不合法，直接把进程杀掉（`ActiveServices.killMisbehavingService`
 * → `crashApplicationWithType(... "Bad notification for startForeground")`）。
 * 而且我们把它包在 runCatching 里：异常被吞掉、startForeground 实际没生效，
 * 5 秒后系统再补一刀 `ForegroundServiceDidNotStartInTimeException`。
 *
 * 现在拆成职责单一的两条：
 * - [NOTIF_CALL] 2001：前台服务的常驻通知，**通道永远固定**在低优先级通道，
 *   只随通话内容更新，绝不切换通道 —— 前台服务的通知就该稳定不变。
 * - [NOTIF_ALERT] 2002：来电提醒，高优先级 + fullScreenIntent + 接听/挂断，
 *   由 [sync] 按「是否来电中」「界面是否可见」决定发送还是撤销。
 *
 * 这样「界面可见时不要 heads-up」就只是撤掉提醒通知，不用去动前台服务的通知。
 */
object CallNotificationManager {

    private const val TAG = "CallNotif"

    const val CHANNEL_INCOMING = "xperia_dialer_incoming"
    const val CHANNEL_ONGOING = "xperia_dialer_ongoing"

    /** 前台服务常驻通知 */
    const val NOTIF_CALL = 2001

    /** 来电提醒通知（可撤销） */
    const val NOTIF_ALERT = 2002

    /**
     * 来电界面是否已经在前台。
     *
     * 界面在前台时再弹 heads-up 纯属干扰 —— 屏幕上已经有接听/挂断按钮了。
     * 这里只影响 [NOTIF_ALERT] 的去留，与前台服务通知无关。
     */
    @Volatile
    private var uiVisible = false

    fun setUiVisible(context: Context, visible: Boolean) {
        if (uiVisible == visible) return
        uiVisible = visible
        Log.i(TAG, "uiVisible=$visible calls=${CallManager.uiState.value.calls.size}")
        sync(context)
    }

    /**
     * 按当前通话状态同步提醒通知。
     *
     * 幂等：随时调用都安全，内部自己判断该发还是该撤。
     */
    fun sync(context: Context) {
        val state = CallManager.uiState.value
        val nm = NotificationManagerCompat.from(context)

        // 没有通话 / 界面预览（不是真实通话）/ 没有通知权限：都不该有提醒
        if (state.calls.isEmpty() || CallManager.isPreviewing ||
            !hasNotificationPermission(context)
        ) {
            runCatching { nm.cancel(NOTIF_ALERT) }
            return
        }

        val ringing = state.calls.any { it.phase == CallPhase.RINGING }
        if (ringing && !uiVisible) {
            runCatching { nm.notify(NOTIF_ALERT, buildAlert(context)) }
                .onFailure { Log.e(TAG, "post alert failed", it) }
        } else {
            // 已接听，或界面就在眼前：撤掉提醒（常驻通知由前台服务自己维护）
            runCatching { nm.cancel(NOTIF_ALERT) }
        }
    }

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)

        val incoming = NotificationChannel(
            CHANNEL_INCOMING,
            context.getString(R.string.notification_channel_incoming),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "来电提醒"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val ongoing = NotificationChannel(
            CHANNEL_ONGOING,
            context.getString(R.string.notification_channel_ongoing),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "正在进行的通话"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        nm.createNotificationChannel(incoming)
        nm.createNotificationChannel(ongoing)
    }

    /**
     * 前台服务的常驻通知。
     *
     * **通道固定为 [CHANNEL_ONGOING]**，永远不切换 —— 切换通道要重新
     * startForeground，而那条路径正是之前崩溃的地方。
     * 通话中给挂断按钮；来电中不给按钮（按钮属于 [buildAlert]，
     * 否则通知栏里会出现两份重复的接听/挂断）。
     */
    fun buildOngoing(context: Context): Notification {
        val info = CallManager.uiState.value.calls.firstOrNull()
        val ringing = info?.phase == CallPhase.RINGING
        val title = titleOf(context, info)

        val builder = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(title)
            .setContentText(subtitleOf(context, info, ringing))
            .setContentIntent(contentIntent(context))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (!ringing) {
            val person = personOf(title)
            builder.setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    person,
                    CallActionReceiver.pendingIntent(context, CallActionReceiver.ACTION_HANGUP)
                )
            ).addPerson(person)
        }
        return builder.build()
    }

    /**
     * 来电提醒：高优先级 + 全屏意图 + 接听 / 挂断。
     *
     * 只负责「提醒」，所以界面可见时会被 [sync] 撤掉。
     */
    fun buildAlert(context: Context): Notification {
        val calls = CallManager.uiState.value.calls
        val info = calls.firstOrNull { it.phase == CallPhase.RINGING } ?: calls.firstOrNull()
        val title = titleOf(context, info)
        val person = personOf(title)
        val content = contentIntent(context)

        val answer = CallActionReceiver.pendingIntent(context, CallActionReceiver.ACTION_ANSWER)
        val hangup = CallActionReceiver.pendingIntent(context, CallActionReceiver.ACTION_HANGUP)

        return NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(title)
            .setContentText(subtitleOf(context, info, ringing = true))
            .setContentIntent(content)
            // 锁屏 / 后台时全屏拉起来电界面（Android 14+ 需要 USE_FULL_SCREEN_INTENT）
            .setFullScreenIntent(content, true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, hangup, answer))
            .addPerson(person)
            .build()
    }

    /**
     * 兜底通知：内容最小、通道固定、静音。
     *
     * 一旦系统判定上面的通知「坏」，[CallForegroundService] 会退到这一条，
     * 至少保证 `startForeground()` 成功 —— 否则 5 秒后会被
     * `ForegroundServiceDidNotStartInTimeException` 杀掉进程。
     */
    fun buildMinimal(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(context.getString(R.string.unknown_number))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    /** Android 13+ 通知权限是否授予 */
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun cancel(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        runCatching { nm.cancel(NOTIF_ALERT) }
        runCatching { nm.cancel(NOTIF_CALL) }
    }

    // ------------------------------------------------------------------ 内部

    private fun titleOf(context: Context, info: CallInfo?): String {
        val displayName = info?.displayName?.takeIf { it.isNotBlank() }
        val rawNumber = info?.number?.takeIf { it.isNotBlank() }
        return displayName ?: rawNumber ?: context.getString(R.string.unknown_number)
    }

    private fun subtitleOf(context: Context, info: CallInfo?, ringing: Boolean): String {
        val hasName = info?.displayName?.isNotBlank() == true
        val number = info?.number.orEmpty()
        return when {
            hasName && number.isNotBlank() -> number
            ringing -> "来电"
            else -> "通话中"
        }
    }

    private fun personOf(title: String): Person = Person.Builder()
        .setName(title)
        .setImportant(true)
        .build()

    private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        10,
        Intent(context, InCallActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        ),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
