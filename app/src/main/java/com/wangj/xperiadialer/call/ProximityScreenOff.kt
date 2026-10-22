package com.wangj.xperiadialer.call

import android.content.Context
import android.os.PowerManager
import android.telecom.CallAudioState
import android.util.Log

/**
 * 通话时贴近耳朵自动息屏。
 *
 * 第三方 InCallService 不会自动获得这个能力（那是系统拨号器才有的待遇），
 * 必须自己持有 PROXIMITY_SCREEN_OFF_WAKE_LOCK。系统会接管距离传感器：
 * 物体靠近 → 关屏，离开 → 亮屏。
 *
 * 触发条件：通话已接通（ACTIVE）且走听筒。
 * 免提 / 蓝牙 / 有线耳机时不该息屏，否则用户看不到界面。
 */
object ProximityScreenOff {

    private const val TAG = "ProximityScreenOff"

    private var wakeLock: PowerManager.WakeLock? = null

    /** 根据当前通话状态决定是否持有 proximity 锁 */
    fun update(context: Context, state: CallUiState) {
        val hasActiveCall = state.calls.any { it.phase == CallPhase.ACTIVE }
        val earpieceOnly = state.audioRoute == CallAudioState.ROUTE_EARPIECE
        setEnabled(context.applicationContext, hasActiveCall && earpieceOnly)
    }

    fun release() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure { Log.w(TAG, "release failed", it) }
        wakeLock = null
        Log.i(TAG, "released")
    }

    @Suppress("DEPRECATION")
    private fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            val lock = wakeLock ?: run {
                val pm = context.getSystemService(PowerManager::class.java) ?: return
                pm.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "XperiaDialer:proximity"
                ).apply {
                    setReferenceCounted(false)
                    wakeLock = this
                }
            }
            if (!lock.isHeld) {
                runCatching { lock.acquire() }
                    .onFailure { Log.w(TAG, "acquire failed", it) }
                Log.i(TAG, "acquired (screen off on proximity)")
            }
        } else {
            wakeLock?.let {
                if (it.isHeld) {
                    runCatching { it.release() }
                        .onFailure { e -> Log.w(TAG, "release failed", e) }
                    Log.i(TAG, "released (state changed)")
                }
            }
        }
    }
}
