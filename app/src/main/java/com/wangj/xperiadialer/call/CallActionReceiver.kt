package com.wangj.xperiadialer.call

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 通知上的「接听 / 挂断」按钮。
 *
 * 走 BroadcastReceiver 而不是 Activity，保证即使来电 UI 没起来
 * （例如进程刚被 Telecom 拉起）也能完成接听与拒接。
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive ${intent.action}")
        when (intent.action) {
            ACTION_ANSWER -> CallManager.answer()
            ACTION_HANGUP -> if (CallManager.isRinging()) CallManager.reject() else CallManager.hangup()
        }
    }

    companion object {
        private const val TAG = "CallActionReceiver"

        const val ACTION_ANSWER = "com.wangj.xperiadialer.action.ANSWER"
        const val ACTION_HANGUP = "com.wangj.xperiadialer.action.HANGUP"

        fun pendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, CallActionReceiver::class.java)
                .setAction(action)
                .setPackage(context.packageName)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
        }
    }
}
