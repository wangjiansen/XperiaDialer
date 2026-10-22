package com.wangj.xperiadialer.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Window

/**
 * 让窗口跑在「系统当前允许的最高刷新率」上。
 *
 * ## 为什么需要
 *
 * Compose 的动画和列表滚动都由 Choreographer 驱动，帧率 = 屏幕 vsync 频率。
 * 屏幕按 60Hz 走，滑动再怎么写也只有 60 帧。系统的自适应很保守：不主动提要求的话，
 * 普通 App（非游戏）基本钉在最低档。窗口上加 `preferredRefreshRate` 就是
 * 「这个窗口需要高帧率」的正式信号。
 *
 * ## 三个必须知道的限制
 *
 * 1. **不越过用户在系统里定的上限**。`peak_refresh_rate` 就是设置里那个高刷开关
 *    （60 / 90 / 120），设备级、和续航直接相关。用户选 60 就不该被 App 顶掉，
 *    而且系统本来也会把超出部分丢掉。所以取「面板支持」和「系统允许」的较小值。
 * 2. **不能跨分辨率选模式**。`supportedModes` 里既有 1096x2560 也有 1644x3840，
 *    只按刷新率挑会挑到另一档分辨率的模式，画面尺寸直接变了。
 * 3. **别写死 120**。系统上限配不上整档模式时（比如限到 75Hz），要退到
 *    「不高于它的最高模式」。
 */
object HighRefresh {

    private const val TAG = "HighRefresh"

    /** 在 `Activity.onCreate` 里调一次即可；窗口属性会一直生效 */
    fun apply(activity: Activity) {
        val window = activity.window ?: return
        runCatching { applyToWindow(window) }
            .onFailure { Log.w(TAG, "request high refresh failed", it) }
    }

    private fun applyToWindow(window: Window) {
        @Suppress("DEPRECATION")
        val display = window.windowManager?.defaultDisplay ?: return

        val current = display.mode ?: return
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth &&
                it.physicalHeight == current.physicalHeight
        }
        val panelMax = sameSize.maxByOrNull { it.refreshRate } ?: return

        val systemPeak = systemPeakRefreshRate(window.context)
        val allowed = if (systemPeak > 0f) {
            minOf(panelMax.refreshRate, systemPeak)
        } else {
            panelMax.refreshRate
        }

        // 系统上限配不上整档模式时，退到「不高于它的最高模式」
        val target = sameSize
            .filter { it.refreshRate <= allowed + 0.01f }
            .maxByOrNull { it.refreshRate }

        if (target != null && target.modeId != current.modeId) {
            val lp = window.attributes
            lp.preferredRefreshRate = target.refreshRate
            lp.preferredDisplayModeId = target.modeId
            window.attributes = lp

            /*
             * Android 15 起窗口属性只是「偏好」，逐窗口真正的帧率信号是
             * View#requestedFrameRate。面板是可变刷新率时两个都给：
             * 前者选模式，后者告诉 SurfaceFlinger 这个窗口的期望帧率。
             */
            if (Build.VERSION.SDK_INT >= 35) {
                runCatching { window.decorView.requestedFrameRate = target.refreshRate }
            }
        }

        Log.i(
            TAG,
            "panelMax=${panelMax.refreshRate}Hz systemPeak=${systemPeak}Hz " +
                "current=${current.refreshRate}Hz/${current.modeId} " +
                "target=${target?.refreshRate}Hz/${target?.modeId}"
        )
    }

    /**
     * 系统设置里的刷新率上限（0 表示用户没设过，交给系统自己决定）。
     *
     * 读取 Settings.System 不需要权限，写才需要 WRITE_SETTINGS。
     */
    private fun systemPeakRefreshRate(context: Context): Float =
        runCatching {
            Settings.System.getFloat(context.contentResolver, "peak_refresh_rate", 0f)
        }.getOrDefault(0f)
}
