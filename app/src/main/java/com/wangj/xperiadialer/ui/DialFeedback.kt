package com.wangj.xperiadialer.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 拨号按键的声音 + 震动反馈。
 *
 * ## 为什么用 DTMF 而不是系统点击音
 *
 * `AudioManager.playSoundEffect(CLICK)` 放的是系统「触摸提示音」，和拨号盘该有的
 * 双音多频（DTMF）完全是两种东西 —— 后者才是电话键盘的音色，也是系统拨号器的做法。
 * 所以用 `ToneGenerator(STREAM_DTMF)` 自己发声。
 *
 * ## 两道开关的归属
 *
 * - **声音不跟随「触摸提示音」**。那个开关管的是界面点击音，而拨号盘该有的是 DTMF；
 *   系统拨号器（Google Phone）也是独立的「拨号盘提示音」开关。本机这个系统开关
 *   是关的，若照着它走，按键就永远无声 —— 与需求相反。
 *   声音改由**铃声模式**兜底：`STREAM_DTMF` 在系统里是 alias 到 `STREAM_RING` 的，
 *   静音 / 振动模式下音量自然为 0，不用自己判断。
 * - **震动跟随「触摸反馈」**：`View.performHapticFeedback(VIRTUAL_KEY)` 由系统检查
 *   该开关（本机为开），关掉时调用直接返回 false。不需要 VIBRATE 权限 ——
 *   权限只在直接调 Vibrator 时才要。
 *
 * ## ToneGenerator 的生命周期
 *
 * 构造一次要占一条 AudioTrack，不能每按一个键就 new 一个；也不能永久持有，
 * 会白占音频资源。所以：懒创建 → 每次按键续命 → 闲置 [IDLE_RELEASE_MS] 后释放。
 * 创建和释放都放在单线程池里，避免第一次按键时在主线程上卡一下。
 */
object DialFeedback {

    private const val TAG = "DialFeedback"

    /** DTMF 音长：短到不拖尾，长到听得清 */
    private const val TONE_MS = 65

    /** 空闲多久后释放 ToneGenerator */
    private const val IDLE_RELEASE_MS = 2500L

    /** DTMF 音量（0..100）。100 太冲，80 和系统拨号盘接近 */
    private const val VOLUME = 80

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dial-tone").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /** 每次按键 +1，用来判断「释放时是否已经有新的按键进来」 */
    private val tick = AtomicInteger(0)

    @Volatile
    private var generator: ToneGenerator? = null

    /**
     * 按键反馈。必须在主线程调用（`performHapticFeedback` 的要求）。
     *
     * @param digit 键面字符，0-9 / * / #
     * @param sound false 表示只要震动（不需要本地发声的场景）
     */
    fun key(digit: Char, view: View?, sound: Boolean = true) {
        if (sound) playTone(digit)
        // 系统会自己判断「触摸反馈」开关，关掉时这次调用直接返回 false
        view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun playTone(digit: Char) {
        val tone = toneOf(digit) ?: return
        val current = tick.incrementAndGet()

        worker.execute {
            val gen = generator ?: runCatching {
                ToneGenerator(AudioManager.STREAM_DTMF, VOLUME)
            }.onFailure {
                Log.w(TAG, "ToneGenerator unavailable", it)
            }.getOrNull()?.also { generator = it }

            if (gen == null) return@execute
            runCatching { gen.startTone(tone, TONE_MS) }
                .onFailure { Log.w(TAG, "startTone($digit) failed", it) }
        }

        // 续命：只要还在按，就不释放
        main.postDelayed({ releaseIfIdle(current) }, IDLE_RELEASE_MS)
    }

    private fun releaseIfIdle(token: Int) {
        if (tick.get() != token) return   // 期间又按了键，等下一次
        worker.execute {
            runCatching { generator?.release() }
            generator = null
        }
    }

    /**
     * 键面字符 → DTMF 音。
     *
     * `TONE_DTMF_0` 起 0-9 连续（S/P/A/B/C/D 依次是 10..15），
     * 所以数字直接加偏移，星号/井号用 S / P —— 注意常量名不是 STAR/POUND。
     */
    private fun toneOf(digit: Char): Int? = when (digit) {
        in '0'..'9' -> ToneGenerator.TONE_DTMF_0 + (digit - '0')
        '*' -> ToneGenerator.TONE_DTMF_S
        '#' -> ToneGenerator.TONE_DTMF_P
        else -> null
    }

}
