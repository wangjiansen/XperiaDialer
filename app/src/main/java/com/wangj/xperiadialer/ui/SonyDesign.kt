package com.wangj.xperiadialer.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 调色板与尺寸全部提取自系统自带 Sony Dialer
 * （`/product/priv-app/Dialer/Dialer.apk`，包名 com.sonymobile.android.dialer）。
 *
 * 提取方式：`aapt2 dump resources Dialer.apk`，对照真机截图校对。
 * 目的是让自研拨号器的视觉与系统原生一致，减少割裂感。
 */
object SonyColors {

    /** 主题蓝：拨号盘数字、选中态、链接 */
    val Theme = Color(0xFF2A56C6)
    val ThemeDark = Color(0xFF1C3AA9)

    /** 接听键绿 */
    val CallGreen = Color(0xFF00C853)

    /** 挂断键红 */
    val EndCallRed = Color(0xFFBD2A2A)
    val Red = Color(0xFFC53929)

    /** 页面底色（不是纯白，比 #FFFFFF 略灰） */
    val Background = Color(0xFFFAFAFA)

    /** Sony 强调品红：通话记录悬浮拨号键、角标 */
    val Accent = Color(0xFFF50057)

    val Divider = Color(0xFFD8D8D8)
    val DialpadBg = Color(0xFFFFFFFF)

    val TextPrimary = Color(0xFF212121)
    val TextSecondary = Color(0xFF757575)
    val TextTertiary = Color(0xFF9E9E9E)

    /** 未激活的功能键底色 */
    val KeyIdle = Color(0xFFF0F0F0)
    val KeyIdleText = Color(0xFF424242)

    /** 字母头像调色板：Material 500，与 Sony Dialer 的 letter_tile_colors 完全一致 */
    private val AvatarPalette = intArrayOf(
        0xFFDB4437.toInt(), 0xFFE91E63.toInt(), 0xFF9C27B0.toInt(), 0xFF673AB7.toInt(),
        0xFF3F51B5.toInt(), 0xFF4285F4.toInt(), 0xFF039BE5.toInt(), 0xFF0097A7.toInt(),
        0xFF009688.toInt(), 0xFF0F9D58.toInt(), 0xFF689F38.toInt(), 0xFFEF6C00.toInt(),
        0xFFFF5722.toInt(), 0xFF757575.toInt(),
    )

    /** 按号码稳定取色：同一个号码每次进来颜色一致 */
    fun avatarColor(key: String): Color {
        if (key.isEmpty()) return Color(0xFFCCCCCC)
        val idx = abs(key.hashCode()) % AvatarPalette.size
        return Color(AvatarPalette[idx])
    }
}

/**
 * 圆形字母头像，尺寸与配色对齐 Sony Dialer 的联系人头像。
 *
 * @param name 有姓名取首字；无姓名时显示号码后两位，保证不同号码视觉可区分
 * @param colorKey 取色依据，传号码保证颜色稳定
 */
@Composable
fun LetterAvatar(
    name: String,
    number: String,
    colorKey: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val label = when {
        name.isNotBlank() -> name.trim().take(1).uppercase()
        number.length >= 2 -> number.takeLast(2)
        number.isNotEmpty() -> number
        else -> "?"
    }
    val fontSp = with(LocalDensity.current) { (size.toPx() * 0.38f).toSp() }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(SonyColors.avatarColor(colorKey)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = fontSp,
            fontWeight = FontWeight.Normal,
        )
    }
}

/** Sony 拨号盘按键的字母标注 */
private val KEY_LETTERS = mapOf(
    '1' to "",
    '2' to "ABC",
    '3' to "DEF",
    '4' to "GHI",
    '5' to "JKL",
    '6' to "MNO",
    '7' to "PQRS",
    '8' to "TUV",
    '9' to "WXYZ",
    '*' to "",
    '0' to "+",
    '#' to "",
)

/**
 * 单个拨号键。
 *
 * Sony 原版的按键**没有背景容器**，就是浮在白底上的数字 + 下方小字母，
 * 只有按下时出现涟漪。键高 64dp、数字 36sp。
 *
 * 关键点：1 / * / # 没有字母，如果让 Column 自然居中，它们的数字会比 2/3 低半行。
 * 所以字母区用**固定高度的槽位**（有字母时放文字，没有时留空），
 * 并且显式设置 lineHeight —— Material3 的 Text 不传 style 时会继承 bodyLarge
 * 的 24sp 行高，光改 fontSize 不会改变行高，会导致错位。
 */
@Composable
fun SonyDialKey(
    digit: Char,
    onDigit: (Char) -> Unit,
    modifier: Modifier = Modifier,
    /** 是否播放按键音（通话中的键音由 Telecom 送给对端，见调用处说明） */
    sound: Boolean = true,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }

    /*
     * 反馈要落在"按下去"那一刻，不能等抬手。
     *
     * clickable 只在 onClick（松手）时回调，按着一半又滑出去也不会响，
     * 手感就是"点了没反应"。这里监听交互流里的 Press，手指一落下就发声震动。
     */
    LaunchedEffect(interaction, digit, sound) {
        interaction.interactions.collect { event ->
            if (event is PressInteraction.Press) {
                DialFeedback.key(digit, view, sound)
            }
        }
    }

    Column(
        modifier = modifier
            .height(64.dp)
            .fillMaxWidth()
            /*
             * 涟漪要**无边界**（bounded = false）且半径够大，和原机一致。
             *
             * 默认的 LocalIndication 是 bounded ripple：按节点形状铺满整个键
             * （约 110×64 的圆角矩形），点一下像一大块方块闪一下，很笨重；
             * 而原机的涟漪是从按下点扩散、范围明显超出键本身的那种大圆。
             *
             * 因此这里不能保留 clip(RoundedCornerShape) —— clip 会把涟漪裁在
             * 键的内部。键本身没有背景色，去掉 clip 对静态外观没有影响。
             */
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = KEY_RIPPLE_RADIUS),
                onClick = { onDigit(digit) },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = digit.toString(),
            fontSize = 34.sp,
            lineHeight = 38.sp,
            color = SonyColors.Theme,
            fontWeight = FontWeight.Normal,
        )
        // 固定高度字母槽：有无字母的键必须占同样的垂直空间
        Box(
            modifier = Modifier.height(LETTER_SLOT_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            val letters = KEY_LETTERS[digit].orEmpty()
            if (letters.isNotEmpty()) {
                Text(
                    text = letters,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = SonyColors.TextTertiary,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

/** 字母槽高度：视觉上等于 10sp 文字 + 少量呼吸空间 */
private val LETTER_SLOT_HEIGHT = 14.dp

/** 按键涟漪半径：远大于键高(64dp)，对齐原机那种「扩散很远」的大圆 */
private val KEY_RIPPLE_RADIUS = 72.dp

/** 3×4 完整拨号键盘 */
@Composable
fun SonyDialPad(
    onDigit: (Char) -> Unit,
    modifier: Modifier = Modifier,
    sound: Boolean = true,
) {
    val keys = "123456789*0#".toList()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.chunked(3).forEach { row ->
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { digit ->
                    SonyDialKey(
                        digit = digit,
                        onDigit = onDigit,
                        modifier = Modifier.weight(1f),
                        sound = sound,
                    )
                }
            }
        }
    }
}

/**
 * 按住连发。
 *
 * 按下立即触发一次；持续按住超过 [initialDelayMs] 后按 [intervalMs] 连续触发，
 * 松手或 [enabled] 变为 false 时停止。用于删除键这类「长按快删」的交互 ——
 * 只靠 onClick 的话清一个长号码要点几十次。
 *
 * 用法上要替代 `clickable`（两者都消费 tap，同时加会冲突）。
 *
 * 几个必须注意的点（都是踩过的坑）：
 * - `onTrigger` / `enabled` 用 rememberUpdatedState 读取，**不能**作为 pointerInput 的 key。
 *   否则每次重组 lambda 都是新实例 → 手势协程被重启 → 下面的 cancel 执行不到 →
 *   连发协程泄漏，表现就是「松手后还在删，之后输入的数字立刻被吃掉」。
 * - pointerInput 外层用 try/finally，任何取消路径都能收尾。
 * - 连发循环里检查 enabled，号码删空就自动停，不用等松手。
 */
fun Modifier.holdToRepeat(
    enabled: Boolean = true,
    initialDelayMs: Long = 400L,
    intervalMs: Long = 70L,
    onTrigger: () -> Unit,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    val currentTrigger by rememberUpdatedState(onTrigger)
    val repeatJob = remember { mutableStateOf<Job?>(null) }

    // composition 销毁时兜底，避免协程悬挂
    DisposableEffect(Unit) {
        onDispose {
            repeatJob.value?.cancel()
            repeatJob.value = null
        }
    }

    pointerInput(Unit) {
        try {
            detectTapGestures(
                onPress = {
                    if (!currentEnabled) return@detectTapGestures
                    currentTrigger()
                    repeatJob.value?.cancel()
                    repeatJob.value = scope.launch {
                        delay(initialDelayMs)
                        while (isActive) {
                            if (!currentEnabled) break
                            currentTrigger()
                            delay(intervalMs)
                        }
                    }
                    tryAwaitRelease()
                    repeatJob.value?.cancel()
                    repeatJob.value = null
                }
            )
        } finally {
            repeatJob.value?.cancel()
            repeatJob.value = null
        }
    }
}
