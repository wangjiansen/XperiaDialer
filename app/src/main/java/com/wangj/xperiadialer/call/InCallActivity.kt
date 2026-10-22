package com.wangj.xperiadialer.call

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wangj.xperiadialer.ui.HighRefresh
import com.wangj.xperiadialer.ui.XperiaDialerTheme
import kotlinx.coroutines.launch

/**
 * 来电 / 通话中界面。
 *
 * 锁屏相关配置在 Manifest 里（showWhenLocked / turnScreenOn），
 * 这里再兜一层代码调用，避免部分 ROM 不认 Manifest 属性。
 */
class InCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 来电页的呼吸动画、滑动接听都受益于高刷
        HighRefresh.apply(this)
        setupLockScreenFlags()
        Log.i(TAG, "onCreate")
        setContent {
            XperiaDialerTheme {
                InCallScreen(onFinish = { finishAndRemoveTask() })
            }
        }
    }

    /**
     * 通话中贴近耳朵息屏。
     *
     * 只在界面 STARTED 期间生效：用户按 home 最小化后必须释放，
     * 否则遮住传感器会让屏幕一直黑着，点通知也回不到界面。
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.uiState.collect { state ->
                    ProximityScreenOff.update(this@InCallActivity, state)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 界面在前台：通知降级，避免 heads-up 盖住界面顶部
        CallNotificationManager.setUiVisible(this, true)
    }

    override fun onPause() {
        // 退到后台（含按 HOME）：通知恢复高优先级 + fullScreenIntent
        CallNotificationManager.setUiVisible(this, false)
        super.onPause()
    }

    override fun onStop() {
        ProximityScreenOff.release()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "onNewIntent")
    }

    private fun setupLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        ProximityScreenOff.release()
        // 预览模式收尾：界面关闭时清掉假状态，避免主页看到残留通话
        CallManager.clearPreview()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "InCallActivity"
    }
}
