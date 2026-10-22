package com.wangj.xperiadialer.call

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log

/**
 * 自绘来电/通话界面的入口。
 *
 * 只有在 Manifest 里同时声明了
 *   android.permission.BIND_INCALL_SERVICE
 *   android.telecom.IN_CALL_SERVICE_UI   = true
 *   android.telecom.IN_CALL_SERVICE_RINGING = true
 * 并且本 App 被选为默认电话应用（ROLE_DIALER），Telecom 才会绑定到这里。
 */
class InCallServiceImpl : InCallService() {

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.i(TAG, "onCallAdded state=${call.details.state}")

        CallManager.attach(this)
        CallManager.addCall(call)

        // Android 14+ 要求通话前台服务声明 phoneCall 类型
        CallForegroundService.update(this, "onCallAdded")

        launchInCallUi()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.i(TAG, "onCallRemoved")
        CallManager.removeCall(call)

        if (!CallManager.hasCalls()) {
            CallForegroundService.stop(this)
            CallManager.detach()
        } else {
            CallForegroundService.update(this, "onCallRemoved(还有通话)")
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        CallManager.onAudioStateChanged(audioState)
    }

    override fun onCanAddCallChanged(canAddCall: Boolean) {
        super.onCanAddCallChanged(canAddCall)
        Log.i(TAG, "onCanAddCallChanged $canAddCall")
    }

    private fun launchInCallUi() {
        val intent = Intent(this, InCallActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.e(TAG, "start InCallActivity failed", it) }
    }

    companion object {
        private const val TAG = "InCallServiceImpl"
    }
}
