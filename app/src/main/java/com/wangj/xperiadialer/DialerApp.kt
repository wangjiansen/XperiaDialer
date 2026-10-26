package com.wangj.xperiadialer

import android.app.Application
import android.util.Log
import com.wangj.xperiadialer.call.CallForegroundService
import com.wangj.xperiadialer.call.CallNotificationManager
import com.wangj.xperiadialer.data.PhoneBook
import com.wangj.xperiadialer.data.PhoneLocationDb
import java.util.concurrent.Executors

class DialerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 通知渠道必须在第一次发通知之前创建
        CallNotificationManager.createChannels(this)
        installForegroundServiceCrashGuard()

        // 号段库 4.6MB + 黄页库 600 条，提前在后台加载好，避免来电筛查时现读拖慢响应
        // （CallScreeningService 只有 5 秒窗口，超时会导致不响铃）
        Executors.newSingleThreadExecutor().execute {
            val start = System.currentTimeMillis()
            val ok = PhoneLocationDb.ensureLoaded(this)
            Log.i(
                "DialerApp",
                "phone.dat preload ${if (ok) "ok" else "failed"} in ${System.currentTimeMillis() - start}ms"
            )

            val t2 = System.currentTimeMillis()
            val bookOk = PhoneBook.ensureLoaded(this)
            Log.i(
                "DialerApp",
                "phonebook preload ${if (bookOk) "ok, ${PhoneBook.size} entries" else "failed"} " +
                    "in ${System.currentTimeMillis() - t2}ms"
            )
        }
    }

    /**
     * 吞掉「前台服务通知失败」这一类系统异常。
     *
     * ## 为什么需要
     *
     * 系统判定前台服务通知不合法时，不是给应用抛一个可以 try/catch 的异常，
     * 而是走 `ActiveServices.killMisbehavingService(...)`：通过 Handler 在主线程
     * 抛 [android.app.RemoteServiceException]，直接把进程干掉。同理，没能在 5 秒内
     * 进入前台会抛 `ForegroundServiceDidNotStartInTimeException`。
     *
     * 这两种情况都发生在**通话进行中**：界面、音频、Telecom 绑定全都正常，
     * 唯一失败的是"通知没发出去"。为了这一条通知把正在通话的应用杀掉，
     * 用户看到的就是"来电程序异常停止"、通话界面消失 —— 代价完全不成比例。
     *
     * 所以这里只针对这两个异常类型放行（其余异常照旧交给系统默认处理，
     * 保证真 bug 还能正常崩溃上报），同时把半启动成功的前台服务停掉，
     * 让状态干净一点。
     */
    private fun installForegroundServiceCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val name = throwable.javaClass.simpleName
            val isFgsFailure = name == "CannotPostForegroundServiceNotificationException" ||
                name == "ForegroundServiceDidNotStartInTimeException"

            if (isFgsFailure) {
                Log.e("DialerApp", "前台服务通知失败，已拦下，通话界面继续运行：$name", throwable)
                runCatching { CallForegroundService.stop(this) }
            } else {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }
}
