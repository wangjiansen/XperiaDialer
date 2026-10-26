package com.wangj.xperiadialer.call

import android.telecom.Call
import android.telecom.CallAudioState
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wangj.xperiadialer.R
import com.wangj.xperiadialer.data.PhoneLocationDb
import com.wangj.xperiadialer.data.SpamAnalyzer
import com.wangj.xperiadialer.data.SpamLevel
import com.wangj.xperiadialer.data.SpamVerdict
import com.wangj.xperiadialer.ui.PhoneAvatar
import com.wangj.xperiadialer.ui.SonyColors
import com.wangj.xperiadialer.ui.SonyDialPad
import com.wangj.xperiadialer.ui.holdToRepeat
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun InCallScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val state by CallManager.uiState.collectAsStateWithLifecycle()
    val verdicts by CallManager.verdicts.collectAsStateWithLifecycle()
    val calls = state.calls
    val allEnded = calls.isEmpty() || calls.all { it.phase == CallPhase.DISCONNECTED }
    // 只取仍然有效的通话：挂断瞬间的 DISCONNECTED 记录不再当作界面的数据源，
    // 否则会闪一下来电 / 通话中样式
    val info = calls.firstOrNull { it.phase != CallPhase.DISCONNECTED }

    /*
     * 识别结果优先用 CallScreeningService 预写入的，取不到就现场算一遍。
     *
     * 不能只依赖筛查服务：它可能在角色丢失 / 超时被解绑时压根没被系统调用，
     * 那样界面就什么都不显示。而且界面预览（fakeState）根本不经过筛查服务。
     * phone.dat 已在 Application 里预加载，现场算一次是亚毫秒级，主线程完全扛得住。
     */
    val verdict = remember(info?.number, verdicts) {
        val num = info?.number.orEmpty()
        if (num.isBlank()) {
            null
        } else {
            verdicts[PhoneLocationDb.normalize(num)]
                ?: runCatching { SpamAnalyzer.analyze(context, num) }.getOrNull()
        }
    }

    var everHadLive by remember { mutableStateOf(false) }

    // 通话结束立即退出。这里响应状态变化，不再用 400ms 轮询 —— 轮询会造成
    // 「已经挂断了但界面还停留一瞬」的观感。
    LaunchedEffect(allEnded) {
        if (!allEnded) {
            everHadLive = true
        } else if (everHadLive) {
            onFinish()
        }
    }

    // 兜底：界面拉起时通话已经没了（来电瞬间挂断的竞态），立刻退出，一个字都不显示；
    // 否则等一段时间再兜一次，防止状态卡死
    LaunchedEffect(Unit) {
        val first = CallManager.uiState.value.calls
        if (first.isEmpty() || first.all { it.phase == CallPhase.DISCONNECTED }) {
            onFinish()
            return@LaunchedEffect
        }
        delay(1200)
        val later = CallManager.uiState.value.calls
        if (later.isEmpty() || later.all { it.phase == CallPhase.DISCONNECTED }) onFinish()
    }

    // 通话时长计时器
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(info?.state) {
        while (CallManager.uiState.value.calls.firstOrNull()?.state == Call.STATE_ACTIVE) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = SonyColors.Background
    ) {
        if (info == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在挂断…", color = SonyColors.TextSecondary)
            }
            return@Surface
        }

        when (info.phase) {
            CallPhase.RINGING -> IncomingContent(info, verdict)
            else -> OngoingContent(info, state, now, verdict)
        }
    }
}

/**
 * 风险标签徽章 + 归属地。UNKNOWN 且无归属地时整行不显示。
 *
 * @param shownName 界面上已经显示的主标题。黄页命中时主标题就是机构名，
 *                  此时徽章若还打机构名就重复了，改显示分类（如「快递物流」「金融银行」）。
 */
@Composable
private fun VerdictRow(verdict: SpamVerdict?, shownName: String = "") {
    if (verdict == null) return
    val location = verdict.location?.display.orEmpty()
    val carrier = verdict.location?.carrier.orEmpty()
    val sub = listOf(location, carrier).filter { it.isNotBlank() }.joinToString(" · ")

    val duplicated = shownName.isNotBlank() && shownName.trim() == verdict.label
    val badge = if (duplicated) verdict.reason else verdict.label

    if (badge.isBlank() && sub.isBlank()) return

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (badge.isNotBlank()) {
            val bg = when (verdict.level) {
                SpamLevel.SPAM -> SonyColors.EndCallRed
                SpamLevel.CAUTION -> Color(0xFFEF6C00)
                SpamLevel.SAFE -> SonyColors.CallGreen
                SpamLevel.UNKNOWN -> SonyColors.TextTertiary
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(bg)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(badge, color = Color.White, fontSize = 11.sp)
            }
            if (sub.isNotBlank()) Spacer(Modifier.width(8.dp))
        }
        if (sub.isNotBlank()) {
            Text(sub, fontSize = 13.sp, color = SonyColors.TextSecondary)
        }
    }
}

@Composable
private fun IncomingContent(info: CallInfo, verdict: SpamVerdict?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(96.dp))
            PhoneAvatar(
                name = info.displayName,
                number = info.number,
                colorKey = info.number,
                size = 120.dp
            )
            Spacer(Modifier.height(24.dp))
            Text("来电", fontSize = 14.sp, color = SonyColors.TextSecondary)
            Spacer(Modifier.height(10.dp))
            /*
             * 主副文案按「有没有名字」互换：
             * 有名字 → 名字大字（这是人关注的），号码退成小字
             * 没名字 → 号码大字（此时号码是唯一标识）
             * 和系统拨号器一致，别让用户盯着 11 位数字找是谁打来的。
             */
            val hasName = info.displayName.isNotBlank()
            Text(
                text = when {
                    hasName -> info.displayName
                    info.number.isBlank() -> "未知号码"
                    else -> info.number
                },
                fontSize = 30.sp,
                fontWeight = FontWeight.Normal,
                color = SonyColors.TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            if (hasName && info.number.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = info.number,
                    fontSize = 16.sp,
                    color = SonyColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
            // 风险标签 + 归属地
            Spacer(Modifier.height(12.dp))
            VerdictRow(verdict, info.displayName)
        }

        SlideToAnswer(
            onAnswer = { CallManager.answer() },
            onDecline = { CallManager.reject() },
        )
    }
}

/** 滑动接听阈值：拖过这个距离松手才会触发 */
private val SLIDE_THRESHOLD = 130.dp

/** 按钮可移动的最大范围（略大于阈值，留出惯性余量） */
private val SLIDE_MAX_TRAVEL = 160.dp

/** 按钮下方预留的空白，保证下滑拒接有足够行程 */
private val SLIDE_BOTTOM_PAD = 56.dp

/** 提示文字与按钮的间距 */
private val SLIDE_HINT_GAP = 46.dp

/**
 * 滑动接听 —— 还原 AOSP Dialer / Sony Dialer 的来电交互：
 *
 * - 绿色接听键带轻微呼吸动画
 * - 按住后按钮跟随手指竖直移动
 * - 上滑绿色不变，超过阈值松手 = 接听
 * - 下滑过程中按钮渐变红色并换成挂断图标，超过阈值松手 = 拒接
 * - 未达阈值松手 = 弹回原位
 *
 * 布局要点：按钮初始贴顶，只给下滑预留空间；上滑时按钮**溢出**父容器往上走，
 * 这样整块区域高度能压到「按钮 + 一个行程」，不会把界面撑出一大片空白。
 */
@Composable
private fun SlideToAnswer(
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val thresholdPx = with(density) { SLIDE_THRESHOLD.toPx() }
    val maxTravelPx = with(density) { SLIDE_MAX_TRAVEL.toPx() }
    val hintOffsetPx = with(density) { -SLIDE_HINT_GAP.toPx() }

    /*
     * 位移用普通 Float 状态，不用 Animatable。
     *
     * 原来 onVerticalDrag 里每次都是 scope.launch { offsetY.snapTo(...) }：
     * 一个触摸事件起一个协程、抢一次 Animatable 的 mutex。事件一密集（高刷下每秒
     * 120 个）协程就排队，表现为"手指走了、圆圈慢半拍"。现在拖动期间直接赋值，
     * 同步、零分配；只有松手回弹才跑一次 animate()。
     */
    var offsetY by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var crossed by remember { mutableStateOf(false) }

    // 静止时的呼吸动画：幅度加大到 1.0→1.12，视觉上才明显
    val pulse = rememberInfiniteTransition(label = "answerPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1050, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    /*
     * 只有离散量参与重组，连续量（位移进度）只在 draw / layout 的 lambda 里读。
     *
     * 原来 `val progress = offsetY.value / thresholdPx` 写在 composition 里，
     * 拖动时每帧都重组整棵子树（文本、图标、背景色全跟着重算）。高刷下
     * 每秒 120 次重组，正好抵消高刷带来的收益。改成 derivedStateOf 后，
     * 文本只在跨过两个阈值时变一次，颜色 lerp 放到 drawBehind 里只重绘不重组。
     */
    /*
     * 三个展示态都要求 `dragging`：松手后的回弹弹簧（dampingRatio 0.5）会过冲几像素，
     * 冲过 0 点时状态会短暂变成"向下拉" —— 提示文字闪一下红色"松开拒接"。
     * 手指都已经抬起了，还提示"松开"本身也没意义，直接回默认文案。
     */
    val declineMode by remember { derivedStateOf { dragging && offsetY >= thresholdPx * 0.5f } }
    val declineTint by remember { derivedStateOf { dragging && offsetY > 0f } }
    val hint by remember {
        derivedStateOf {
            when {
                !dragging -> "上滑接听 · 下滑拒接"
                offsetY >= thresholdPx * 0.08f -> "松开拒接"
                offsetY <= -thresholdPx * 0.08f -> "松开接听"
                else -> "上滑接听 · 下滑拒接"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp + SLIDE_MAX_TRAVEL + SLIDE_BOTTOM_PAD),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            text = hint,
            fontSize = 13.sp,
            color = if (declineTint) SonyColors.EndCallRed else SonyColors.TextSecondary,
            /*
             * 提示文字跟着圆圈一起走，始终保持在它上方 46dp。
             * 原来位置是固定的：上滑 160dp 时圆圈正好盖住文字，
             * 最需要看"松开接听"的时候反而看不见。
             */
            modifier = Modifier.offset {
                IntOffset(0, (hintOffsetPx + offsetY).roundToInt())
            }
        )

        Box(
            modifier = Modifier
                // offset 的 lambda 在 layout 阶段执行，不触发重组
                .offset { IntOffset(0, offsetY.roundToInt()) }
                .size(72.dp)
                .graphicsLayer {
                    val s = if (dragging) 1.1f else pulseScale
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                // 颜色随下拉进度在绿→红之间插值：放在 draw 阶段读状态，只重绘不重组
                .drawBehind {
                    // 只有在手指按着的时候才按进度变红；回弹过冲不加颜色
                    val t = if (dragging) (offsetY / thresholdPx).coerceIn(0f, 1f) else 0f
                    drawCircle(lerp(SonyColors.CallGreen, SonyColors.EndCallRed, t))
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            crossed = false
                            settleJob?.cancel()
                        },
                        onDragEnd = {
                            dragging = false
                            val v = offsetY
                            when {
                                v <= -thresholdPx -> {
                                    offsetY = 0f
                                    onAnswer()
                                }
                                v >= thresholdPx -> {
                                    offsetY = 0f
                                    onDecline()
                                }
                                else -> {
                                    settleJob?.cancel()
                                    settleJob = scope.launch {
                                        animate(
                                            initialValue = offsetY,
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = 0.5f,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        ) { value, _ -> offsetY = value }
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            crossed = false
                            settleJob?.cancel()
                            settleJob = scope.launch {
                                animate(
                                    initialValue = offsetY,
                                    targetValue = 0f,
                                    animationSpec = spring(dampingRatio = 0.5f)
                                ) { value, _ -> offsetY = value }
                            }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            // 同步赋值：与手指同一帧生效，不排队、不分配协程
                            val next = (offsetY + dragAmount)
                                .coerceIn(-maxTravelPx, maxTravelPx)
                            offsetY = next
                            // 越过阈值时给一次触感反馈
                            val nowCrossed = abs(next) >= thresholdPx
                            if (nowCrossed != crossed) {
                                crossed = nowCrossed
                                if (nowCrossed) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    if (declineMode) R.drawable.ic_sony_call_end else R.drawable.ic_sony_call_white
                ),
                contentDescription = if (declineMode) "拒接" else "接听",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun OngoingContent(info: CallInfo, state: CallUiState, now: Long, verdict: SpamVerdict?) {
    var showDtmf by remember { mutableStateOf(false) }
    // DTMF 输入回显：通话中按键输入的数字要能看到，否则按错了完全无感
    var dtmfBuffer by remember { mutableStateOf("") }

    val durationMs =
        if (info.phase == CallPhase.ACTIVE && info.connectTimeMillis > 0) now - info.connectTimeMillis
        else 0L

    val speakerOn = state.audioRoute == CallAudioState.ROUTE_SPEAKER
    val speakerSupported = state.supportedRoutes and CallAudioState.ROUTE_SPEAKER != 0

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(72.dp))
            PhoneAvatar(
                name = info.displayName,
                number = info.number,
                colorKey = info.number,
                size = 96.dp
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = when (info.phase) {
                    CallPhase.DIALING -> "正在拨号…"
                    CallPhase.HOLDING -> "已保持"
                    CallPhase.ACTIVE -> "通话中"
                    else -> "连接中…"
                },
                fontSize = 14.sp,
                color = SonyColors.TextSecondary
            )
            Spacer(Modifier.height(10.dp))
            // 同来电界面：有名字时名字当主标题，号码退成副标题
            val hasName = info.displayName.isNotBlank()
            Text(
                text = when {
                    hasName -> info.displayName
                    info.number.isBlank() -> "未知号码"
                    else -> info.number
                },
                fontSize = 28.sp,
                fontWeight = FontWeight.Normal,
                color = SonyColors.TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            if (hasName && info.number.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = info.number,
                    fontSize = 15.sp,
                    color = SonyColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                // 拨号中/保持时就别计时了 —— 上面的状态行已经说明了当前状态，
                // 这里再写一句「正在呼叫…」会重复
                text = if (info.phase == CallPhase.ACTIVE) formatDuration(durationMs) else "00:00",
                fontSize = 16.sp,
                color = SonyColors.TextSecondary
            )
            Spacer(Modifier.height(10.dp))
            VerdictRow(verdict, info.displayName)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showDtmf) {
                // 输入回显 + 退格
                if (dtmfBuffer.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        /*
                         * 号码过长时只显示结尾，等价于「向前滚动」。
                         *
                         * 试过两条路，都不可靠：
                         * 1. TextOverflow.Ellipsis —— 固定截末尾补省略号，正好藏住最新输入
                         * 2. wrapContentWidth(unbounded) + clipToBounds —— Text 仍被父宽约束，
                         *    超出部分从**右边**被裁掉，末尾照样丢，左边还露出半个字符
                         *
                         * 现在按实际测量宽度算出能放下几个字符，直接 takeLast 显示，
                         * 末尾必定完整、不会溢出、也不会把字符切成两半。
                         */
                        BoxWithConstraints(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            val measurer = rememberTextMeasurer()
                            val textStyle = TextStyle(
                                fontSize = 24.sp,
                                color = SonyColors.TextPrimary
                            )
                            // 数字在多数中文字体里等宽，用 "0" 的宽度作为单字符宽度基准
                            val charWidth = remember(textStyle) {
                                measurer.measure("0", textStyle).size.width.toFloat()
                                    .coerceAtLeast(1f)
                            }
                            val maxChars = (constraints.maxWidth / charWidth)
                                .toInt()
                                .coerceAtLeast(1)
                            val visible =
                                if (dtmfBuffer.length > maxChars) dtmfBuffer.takeLast(maxChars)
                                else dtmfBuffer

                            Text(
                                text = visible,
                                style = textStyle,
                                softWrap = false,
                                maxLines = 1,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.width(18.dp))
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                // 按住连删，和主页拨号一致
                                .holdToRepeat(onTrigger = { dtmfBuffer = dtmfBuffer.dropLast(1) }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_sony_backspace),
                                contentDescription = "退格",
                                tint = SonyColors.TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                SonyDialPad(
                    onDigit = {
                        dtmfBuffer += it
                        CallManager.playDtmf(it)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                CircleIconButton(
                    label = if (state.isMuted) "取消静音" else "静音",
                    iconRes = if (state.isMuted) R.drawable.ic_sony_mic_off else R.drawable.ic_sony_mic,
                    containerColor = if (state.isMuted) SonyColors.Theme else SonyColors.KeyIdle,
                    contentColor = if (state.isMuted) Color.White else SonyColors.KeyIdleText
                ) { CallManager.setMuted(!state.isMuted) }

                CircleIconButton(
                    label = "键盘",
                    iconRes = R.drawable.ic_sony_dialpad,
                    containerColor = if (showDtmf) SonyColors.Theme else SonyColors.KeyIdle,
                    contentColor = if (showDtmf) Color.White else SonyColors.KeyIdleText
                ) { showDtmf = !showDtmf }

                CircleIconButton(
                    label = if (speakerOn) "听筒" else "免提",
                    iconRes = R.drawable.ic_sony_volume_up,
                    containerColor = if (speakerOn) SonyColors.Theme else SonyColors.KeyIdle,
                    contentColor = if (speakerOn) Color.White else SonyColors.KeyIdleText,
                    enabled = speakerSupported
                ) {
                    CallManager.setAudioRoute(
                        if (speakerOn) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            CircleIconButton(
                label = "挂断",
                iconRes = R.drawable.ic_sony_call_end,
                containerColor = SonyColors.EndCallRed,
                size = 72.dp,
                iconSize = 30.dp
            ) { CallManager.hangup() }
            Spacer(Modifier.height(56.dp))
        }
    }
}

/**
 * 圆形功能键：圆内图标 + 圆下文字，与 Sony Dialer 通话界面一致。
 * 图标全部取自系统 Dialer.apk。
 */
@Composable
private fun CircleIconButton(
    label: String,
    @DrawableRes iconRes: Int,
    containerColor: Color,
    contentColor: Color = Color.White,
    size: androidx.compose.ui.unit.Dp = 68.dp,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (enabled) containerColor else SonyColors.KeyIdle
    val fg = if (enabled) contentColor else SonyColors.TextTertiary

    // 不用 ripple：clickable 挂在整个「圆 + 文字」列上，涟漪是矩形的，
    // 落在圆形按钮上会露出一圈黑框。改成按压缩放反馈，干净且方向感明确。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )

    Column(
        modifier = Modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = fg,
                modifier = Modifier.size(iconSize)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (enabled) SonyColors.TextSecondary else SonyColors.TextTertiary
        )
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}
