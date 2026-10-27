package com.wangj.xperiadialer.call

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import com.wangj.xperiadialer.data.ContactsRepository
import com.wangj.xperiadialer.data.PhoneLocationDb
import com.wangj.xperiadialer.data.SpamVerdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val TAG = "CallManager"

/** UI 层关心的通话阶段 */
enum class CallPhase { IDLE, RINGING, DIALING, ACTIVE, HOLDING, DISCONNECTED }

/** 一路通话的快照。P2 阶段会在这里加 spamScore / spamLabel / geoLocation 字段 */
data class CallInfo(
    val id: String = "",
    val number: String = "",
    val displayName: String = "",
    val state: Int = Call.STATE_NEW,
    val direction: Int = Call.Details.DIRECTION_UNKNOWN,
    val connectTimeMillis: Long = 0L,
) {
    val phase: CallPhase
        get() = when (state) {
            /*
             * STATE_RINGING 有两个含义：来电振铃，以及**拨出后对方振铃**。
             * 只按 state 判断会把拨出电话当成来电 —— 界面会渲染出滑动接听、
             * 通知会显示「接听」按钮，甚至 answer() 会作用到自己的拨出电话上。
             *
             * 这里用方向区分：明确 OUTGOING 就是拨出中的「对方振铃」阶段。
             * DIRECTORY_UNKNOWN 一律按来电处理（保守）—— 来电流程是已经真机验证过的，
             * 万一某些 ROM 不填 direction，宁可多显示接听按钮，也不能让真来电没有接听入口。
             */
            Call.STATE_RINGING ->
                if (direction == Call.Details.DIRECTION_OUTGOING) CallPhase.DIALING
                else CallPhase.RINGING

            Call.STATE_DIALING, Call.STATE_CONNECTING -> CallPhase.DIALING
            Call.STATE_ACTIVE -> CallPhase.ACTIVE
            Call.STATE_HOLDING -> CallPhase.HOLDING
            Call.STATE_DISCONNECTED -> CallPhase.DISCONNECTED
            else -> CallPhase.IDLE
        }

    val isIncoming: Boolean get() = direction == Call.Details.DIRECTION_INCOMING
}

data class CallUiState(
    val calls: List<CallInfo> = emptyList(),
    val audioRoute: Int = CallAudioState.ROUTE_EARPIECE,
    val supportedRoutes: Int = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER,
    val isMuted: Boolean = false,
)

/**
 * 进程内唯一的通话状态中心。
 *
 * 被 [InCallServiceImpl] 驱动，UI（Compose）通过 [uiState] 观察。
 * 所有控制方法（接听/挂断/静音/音频路由）最终都回到 android.telecom.Call 与 InCallService。
 */
object CallManager {

    private val lock = Any()
    private val calls = LinkedHashMap<String, Call>()
    private val callbacks = HashMap<String, Call.Callback>()

    @Volatile
    private var service: InCallService? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 查通讯录名字用的后台线程。
     *
     * 单线程足够：来电是一通一通来的，串行查反而不会同时打爆 ContentProvider。
     */
    private val nameExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "call-name-resolver").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /**
     * 号码 → 通讯录姓名。
     *
     * 为什么需要它：`Call.Details.getCallerDisplayName()` 依赖系统拨号器或运营商
     * CNAP 去填，第三方 InCallService 拿到的基本是空字符串 —— 来电界面因此只显示
     * 一串号码。这里自己查一次通讯录补上。
     *
     * 存空字符串算「查过但没有」，避免同一个号码反复回表。
     */
    private val nameCache = ConcurrentHashMap<String, String>()

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    /** 号码 → 骚扰判定结果。由 CallScreeningServiceImpl 在来电时写入，UI 按号码取用 */
    private val _verdicts = MutableStateFlow<Map<String, SpamVerdict>>(emptyMap())
    val verdicts: StateFlow<Map<String, SpamVerdict>> = _verdicts.asStateFlow()

    fun putVerdict(number: String, verdict: SpamVerdict) {
        val key = PhoneLocationDb.normalize(number) ?: number
        if (key.isEmpty()) return
        _verdicts.value = _verdicts.value + (key to verdict)
        Log.i(TAG, "verdict $key -> ${verdict.level} ${verdict.label}")
    }

    fun verdictOf(number: String): SpamVerdict? =
        PhoneLocationDb.normalize(number)?.let { _verdicts.value[it] }

    /**
     * 界面预览用的假通话状态。
     *
     * 非 null 时 [uiState] 由它驱动，真实的 Telecom 回调全部让位（[push] 直接返回）。
     * 真来电进来（[addCall]）会立刻清掉它，保证预览永远不会挡住真实通话。
     */
    @Volatile
    private var fakeState: CallUiState? = null

    val isPreviewing: Boolean get() = fakeState != null

    private fun publishFake(next: CallUiState) {
        fakeState = next
        _uiState.value = next
    }

    // ---------- DEBUG：界面预览（不需要真实来电） ----------
    //
    // 注意：界面上已经不再提供入口（三点菜单里的「预览来电/预览通话」已移除），
    // 这套 API 保留是因为 fakeState 还嵌在接听/拒接/静音/路由等分支里，
    // 拆掉属于独立重构；需要时可通过代码或调试手段直接调用验证界面。

    /** 预览来电界面 */
    fun previewIncoming(number: String, name: String = "") = publishFake(
        CallUiState(
            calls = listOf(
                CallInfo(
                    id = "preview-incoming",
                    number = number,
                    displayName = name,
                    state = Call.STATE_RINGING,
                    direction = Call.Details.DIRECTION_INCOMING,
                )
            )
        )
    )

    /** 预览通话中界面 */
    fun previewActive(number: String, name: String = "") = publishFake(
        CallUiState(
            calls = listOf(
                CallInfo(
                    id = "preview-active",
                    number = number,
                    displayName = name,
                    state = Call.STATE_ACTIVE,
                    direction = Call.Details.DIRECTION_INCOMING,
                    connectTimeMillis = System.currentTimeMillis(),
                )
            )
        )
    )

    /** 预览模式下模拟挂断：先置 DISCONNECTED，让界面走"通话结束自动退出"链路 */
    fun previewEnd() {
        val s = fakeState ?: return
        publishFake(s.copy(calls = s.calls.map { it.copy(state = Call.STATE_DISCONNECTED) }))
    }

    fun clearPreview() {
        if (fakeState == null) return
        fakeState = null
        push()
    }

    fun attach(service: InCallService) {
        synchronized(lock) { this.service = service }
        Log.i(TAG, "attach")
        push()
    }

    fun detach() {
        synchronized(lock) {
            calls.values.forEach { unregisterCallback(it) }
            calls.clear()
            callbacks.clear()
            service = null
        }
        fakeState = null
        _uiState.value = CallUiState()
        _verdicts.value = emptyMap()
        Log.i(TAG, "detach")
    }

    fun hasCalls(): Boolean = synchronized(lock) { calls.isNotEmpty() } || fakeState != null

    // 这里的 it 是 CallInfo（不是 Call），所以用 phase 判断
    fun isRinging(): Boolean =
        _uiState.value.calls.any { it.phase == CallPhase.RINGING }

    fun addCall(call: Call) {
        // 真通话优先：立刻退出预览模式，避免假状态盖住真实来电
        fakeState = null
        val id = idOf(call)
        val cb = object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                Log.i(TAG, "onStateChanged id=${idOf(c)} state=$state")
                push()
            }

            override fun onDetailsChanged(c: Call, details: Call.Details) {
                push()
            }
        }
        synchronized(lock) {
            calls[id] = call
            callbacks[id] = cb
        }
        registerCallback(call, cb)
        Log.i(TAG, "addCall $id state=${call.details.state}")
        // Telecom 塞进来的 callerDisplayName 往往是空的，自己查通讯录补名字
        call.details.handle?.schemeSpecificPart?.let { prefetchContactName(it) }
        push()
    }

    /**
     * 异步解析号码对应的通讯录姓名。
     *
     * 查完回灌 [nameCache] 再推一次状态 —— 来电界面在用户看到号码后的一瞬间
     * 就会从「一串数字」变成「张三」，通知也会跟着重建（[forceNotification]）。
     */
    private fun prefetchContactName(rawNumber: String) {
        val key = PhoneLocationDb.normalize(rawNumber).orEmpty()
        if (key.isEmpty() || nameCache.containsKey(key)) return
        val ctx = synchronized(lock) { service } ?: return
        nameExecutor.execute {
            val name = runCatching { ContactsRepository.lookupName(ctx, rawNumber) }
                .getOrNull().orEmpty()
            nameCache[key] = name
            Log.i(TAG, "resolveName $key -> '${name.ifEmpty { "(未收录)" }}'")
            if (name.isNotEmpty()) {
                mainHandler.post { push(forceNotification = true) }
            }
        }
    }

    fun removeCall(call: Call) {
        val id = idOf(call)
        val cb = synchronized(lock) {
            calls.remove(id)
            callbacks.remove(id)
        }
        cb?.let { unregisterCallback(call, it) }
        Log.i(TAG, "removeCall $id")
        push()
    }

    fun onAudioStateChanged(audioState: CallAudioState?) {
        push(audioState)
    }

    // ---------- 控制 ----------

    fun answer() {
        if (fakeState != null) {
            Log.i(TAG, "answer (preview)")
            previewAnswer()
            return
        }
        ringingCall()?.let {
            Log.i(TAG, "answer")
            it.answer(VideoProfile.STATE_AUDIO_ONLY)
        } ?: Log.w(TAG, "answer: no ringing call")
    }

    /** 预览模式下接听：转成 ACTIVE 假状态，走完整个界面链路 */
    private fun previewAnswer() {
        val s = fakeState ?: return
        val c = s.calls.firstOrNull() ?: return
        publishFake(
            s.copy(
                calls = listOf(
                    c.copy(
                        state = Call.STATE_ACTIVE,
                        connectTimeMillis = System.currentTimeMillis()
                    )
                )
            )
        )
    }

    fun reject() {
        if (fakeState != null) {
            Log.i(TAG, "reject (preview)")
            previewEnd()
            return
        }
        val call = ringingCall()
        if (call == null) {
            Log.w(TAG, "reject: no ringing call")
            return
        }
        Log.i(TAG, "reject")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            call.reject(Call.REJECT_REASON_DECLINED)
        } else {
            @Suppress("DEPRECATION")
            call.reject(false, null)
        }
    }

    fun hangup() {
        if (fakeState != null) {
            Log.i(TAG, "hangup (preview)")
            previewEnd()
            return
        }
        synchronized(lock) { calls.values.toList() }.forEach {
            Log.i(TAG, "disconnect ${idOf(it)}")
            runCatching { it.disconnect() }
        }
    }

    fun toggleHold(): Boolean {
        val call = primaryCall() ?: return false
        return if (call.details.state == Call.STATE_HOLDING) {
            call.unhold(); true
        } else {
            call.hold(); false
        }
    }

    fun setMuted(muted: Boolean) {
        fakeState?.let {
            publishFake(it.copy(isMuted = muted))
            Log.i(TAG, "setMuted $muted (preview)")
            return
        }
        synchronized(lock) { service }?.setMuted(muted)
        Log.i(TAG, "setMuted $muted")
        push()
    }

    /**
     * 音频路由切换。
     *
     * Android 15 起 InCallService.setAudioRoute(Int) 被标记弃用（新体系是
     * CallAudioEndpoint），但新 API 尚未普及且行为在真机上差异较大，
     * 这里继续用旧 API 并显式抑制告警，等 P2 阶段再评估迁移。
     */
    @Suppress("DEPRECATION")
    fun setAudioRoute(route: Int) {
        fakeState?.let {
            publishFake(it.copy(audioRoute = route))
            Log.i(TAG, "setAudioRoute $route (preview)")
            return
        }
        synchronized(lock) { service }?.setAudioRoute(route)
        Log.i(TAG, "setAudioRoute $route")
        push()
    }

    /** DTMF：按下播放，180ms 后停止（模拟按键音时长） */
    fun playDtmf(digit: Char) {
        val call = primaryCall() ?: return
        runCatching {
            call.playDtmfTone(digit)
            mainHandler.postDelayed({ runCatching { call.stopDtmfTone() } }, 180)
        }
    }

    // ---------- 内部 ----------

    private fun primaryCall(): Call? = synchronized(lock) {
        calls.values.firstOrNull { it.details.state == Call.STATE_ACTIVE }
            ?: calls.values.firstOrNull { it.details.state == Call.STATE_HOLDING }
            ?: calls.values.firstOrNull { it.details.state == Call.STATE_DIALING }
            ?: calls.values.firstOrNull { it.details.state == Call.STATE_RINGING }
            ?: calls.values.firstOrNull()
    }

    /**
     * 可接听的来电。
     *
     * 必须排除拨出方向 —— 拨出后对方振铃时状态同样是 STATE_RINGING，
     * 若不过滤，answer() 会作用到自己拨出去的那通电话上。
     */
    private fun ringingCall(): Call? =
        synchronized(lock) {
            calls.values.firstOrNull {
                it.details.state == Call.STATE_RINGING &&
                    it.details.callDirection != Call.Details.DIRECTION_OUTGOING
            }
        }

    @Suppress("DEPRECATION")
    private fun push(audioState: CallAudioState? = null, forceNotification: Boolean = false) {
        // 预览模式期间不接受真实 Telecom 状态，否则假状态会被空快照冲掉
        if (fakeState != null) return
        val snapshot = synchronized(lock) { calls.values.map { it.toInfo() } }
        val svc = synchronized(lock) { service }
        val audio = audioState ?: runCatching { svc?.callAudioState }.getOrNull()

        val next = CallUiState(
            calls = snapshot,
            audioRoute = audio?.route ?: CallAudioState.ROUTE_EARPIECE,
            supportedRoutes = audio?.supportedRouteMask
                ?: (CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER),
            isMuted = audio?.isMuted == true,
        )
        val publish = {
            _uiState.value = next
            syncNotification(next, forceNotification)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            publish()
        } else {
            mainHandler.post(publish)
        }
    }

    /** 上次通知里的「来电中」标记，用来判断要不要重建通知 */
    private var lastNotifRinging: Boolean? = null

    /**
     * 通话状态变化时同步通知。
     *
     * 之前只在 onCallAdded / onCallRemoved 时刷新，导致来电接通后通知仍停留在
     * CallStyle.forIncomingCall（带接听按钮），点了自然没反应。
     * 现在只要「是否来电中」这个标记翻转就重建一次通知。
     *
     * [force] 用于通讯录名字迟到返回的场景：来电中这个标记没变，但通知文案要从
     * 号码换成姓名，必须重建。
     */
    private fun syncNotification(state: CallUiState, force: Boolean = false) {
        if (state.calls.isEmpty()) {
            lastNotifRinging = null
            return
        }
        val ringing = state.calls.any { it.phase == CallPhase.RINGING }
        if (!force && ringing == lastNotifRinging) return
        lastNotifRinging = ringing
        Log.i(TAG, "syncNotification ringing=$ringing force=$force")
        val ctx = synchronized(lock) { service } ?: return
        // 常驻通知刷内容；提醒通知（高优先级 + 全屏 + 接听/挂断）由 sync 决定去留
        CallForegroundService.update(ctx, "syncNotification(ringing=$ringing, force=$force)")
        CallNotificationManager.sync(ctx)
    }

    private fun Call.toInfo(): CallInfo {
        val d = details
        val rawNumber = d.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        /*
         * 名字有三层来源，按可靠性降序：
         * 1. Telecom 给的 callerDisplayName（第三方 InCallService 基本拿不到）
         * 2. 自己查通讯录的结果（[nameCache]，由 [prefetchContactName] 异步填充）
         * 3. 都没有就留空，界面会退回显示号码本身
         */
        val telecomName = d.callerDisplayName?.toString()?.takeIf { it.isNotBlank() }
        val cachedName = rawNumber
            ?.let { PhoneLocationDb.normalize(it) }
            ?.let { nameCache[it] }
            ?.takeIf { it.isNotBlank() }
        return CallInfo(
            id = idOf(this),
            number = rawNumber ?: "",
            displayName = telecomName ?: cachedName.orEmpty(),
            // Android 15 起 Call.getState() 已弃用，改用 Call.Details.getState()
            state = d.state,
            direction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) d.callDirection
            else Call.Details.DIRECTION_UNKNOWN,
            connectTimeMillis = d.connectTimeMillis,
        )
    }

    /**
     * 通话唯一 id。注意不要用 Call.Details 上的 telecomCallId —— 它在部分
     * compileSdk 组合下不可用。进程内用 identityHashCode 足够稳定，
     * 因为 Telecom 每一路通话都会给出新的 Call 对象。
     */
    private fun idOf(call: Call): String = System.identityHashCode(call).toString()

    private fun registerCallback(call: Call, cb: Call.Callback) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                call.registerCallback(cb, Handler(Looper.getMainLooper()))
            } else {
                @Suppress("DEPRECATION")
                call.registerCallback(cb)
            }
        }.onFailure { Log.e(TAG, "registerCallback failed", it) }
    }

    private fun unregisterCallback(call: Call) {
        val cb = callbacks[idOf(call)] ?: return
        unregisterCallback(call, cb)
    }

    private fun unregisterCallback(call: Call, cb: Call.Callback) {
        runCatching { call.unregisterCallback(cb) }
            .onFailure { Log.e(TAG, "unregisterCallback failed", it) }
    }
}
