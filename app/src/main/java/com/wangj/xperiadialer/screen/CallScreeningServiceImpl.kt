package com.wangj.xperiadialer.screen

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.wangj.xperiadialer.call.CallManager
import com.wangj.xperiadialer.data.SpamAnalyzer
import com.wangj.xperiadialer.data.SpamLevel
import com.wangj.xperiadialer.data.SpamVerdict

/**
 * 来电筛选服务 —— P2 骚扰识别入口。
 *
 * !! 硬性约束：onScreenCall 必须在 5 秒内调用 respondToCall()，
 *    超时系统会解绑本服务、丢弃响应，并且手机在这之前不会开始响铃。
 *    所以这里只做纯本地同步查询（号段规则 + 归属地 + 用户标记），
 *    实测单次 <10ms；在线 API 一律留到响应之后异步做。
 *
 * 拦截策略：**默认只提示不拦截**。误拦快递/外卖的代价远高于漏拦，
 * 唯一会真正拦截的是用户自己手动拉黑的号码。
 */
class CallScreeningServiceImpl : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val direction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            details.callDirection
        } else {
            Call.Details.DIRECTION_UNKNOWN
        }

        // 只处理来电，去电不筛查
        val isIncoming = direction == Call.Details.DIRECTION_INCOMING
        val verdict: SpamVerdict? = if (isIncoming && number.isNotBlank()) {
            runCatching { SpamAnalyzer.analyze(this, number) }
                .onFailure { Log.e(TAG, "analyze failed for $number", it) }
                .getOrNull()
        } else {
            null
        }

        if (verdict != null) {
            Log.i(
                TAG,
                "onScreenCall number=$number level=${verdict.level} " +
                        "label=${verdict.label} reason=${verdict.reason} " +
                        "loc=${verdict.location?.display} carrier=${verdict.location?.carrier}"
            )
            // 交给 UI 层展示（来电界面上的标签与归属地）
            CallManager.putVerdict(number, verdict)
        } else {
            Log.i(TAG, "onScreenCall number=$number direction=$direction (skipped)")
        }

        val block = verdict?.block == true && verdict.level == SpamLevel.SPAM

        val response = CallResponse.Builder()
            .setDisallowCall(block)
            .setRejectCall(block)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        runCatching { respondToCall(details, response) }
            .onFailure { Log.e(TAG, "respondToCall failed", it) }
    }

    companion object {
        private const val TAG = "CallScreening"
    }
}
