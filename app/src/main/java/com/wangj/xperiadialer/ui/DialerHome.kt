package com.wangj.xperiadialer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangj.xperiadialer.R
import com.wangj.xperiadialer.data.ContactsRepository
import com.wangj.xperiadialer.data.SpamLevel
import com.wangj.xperiadialer.data.ContactsWriter
import com.wangj.xperiadialer.data.SpamVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 搜索建议最多显示几条（超出在列表内部滚动） */
private const val MAX_SUGGESTIONS = 5

/** 单条建议的行高，用于算列表高度上限 */
private val SUGGESTION_ROW_HEIGHT = 58.dp

/** 建议列表距页面顶部的留白：让开左上角的「收起拨号盘」箭头 */
private val DIALER_TOP_CONTENT_PAD = 48.dp

/**
 * 拨号页 —— 独立的全屏界面，由主页右下角的 FAB 拉起（对齐 Sony 原机）。
 *
 * 结构：T9 搜索建议 / 号码显示 / 数字键盘 / 底部绿色拨号键。
 * 没有顶栏标题，和原机一致；退出靠左上角的收起按钮或系统返回键。
 *
 * 输入数字时，上方空白处填入匹配的联系人（拼音 T9 + 号码双通道），
 * 点一条直接拨打。
 */
@Composable
fun DialerHome(
    number: String,
    verdict: SpamVerdict?,
    results: List<ContactsRepository.Hit>,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDial: () -> Unit,
    onPickContact: (ContactsRepository.Entry) -> Unit,
    onClose: () -> Unit,
) {
    // 作为覆盖层显示，系统栏得自己避让（主页那边是在外层统一处理的）
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /*
     * 筛选结果少于 3 条时才给快捷操作。
     *
     * 结果一大把的时候用户是在「挑人」，多出来三行只会把列表挤走；
     * 结果很少（尤其没有）时他要处理的是手上这个号码，这三行才是主线。
     */
    val showQuickActions = number.isNotEmpty() && results.size < 3
    var pickerOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SonyColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {

        // ---------- 搜索结果 + 号码显示区 ----------
        // 号码靠键盘对齐（而不是垂直居中）：居中会在号码和键盘之间留出大片空白，
        // 号码看起来像浮在半空，和键盘脱节。
        // 上方剩余的弹性空间交给搜索结果，没有结果时由 Spacer 占住 ——
        // 这样号码位置恒定，不会因为结果出现/消失而跳动。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            /*
             * 建议列表与快捷操作**顶部对齐**（对齐原机）。
             *
             * 之前是 Bottom 排列、贴住号码区，结果少的时候列表悬在屏幕中下部，
             * 上方空一大片、下方又挤着号码，看起来像"浮在半空"。
             * 改成 Top：列表从状态栏下方开始排，翻页感稳定，结果多少都不影响起始位置。
             * 号码区和键盘仍然靠 weight 占住底部，不会因为结果多少而上下跳。
             */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // 左上角有「收起拨号盘」的箭头，列表从它下面开始，避免首行被压住
                    .padding(top = DIALER_TOP_CONTENT_PAD),
                verticalArrangement = Arrangement.Top
            ) {
                if (results.isNotEmpty()) {
                    ContactSuggestions(
                        results = results,
                        query = number,
                        onPick = onPickContact
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (showQuickActions) {
                    DialQuickActions(
                        onCreate = {
                            ContactEditorController.open(
                                request = ContactEditorRequest(
                                    detail = null,
                                    initialNumber = number
                                ),
                                onSaved = { msg -> toast(context, msg) },
                                onFailed = { msg -> toast(context, msg) },
                            )
                        },
                        onAddToExisting = { pickerOpen = true },
                        onSendSms = { ContactActions.sendSms(context, number) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (number.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("输入号码", fontSize = 22.sp, color = SonyColors.TextTertiary)
                    }
                } else {
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val measurer = rememberTextMeasurer()
                        val style = TextStyle(fontSize = 30.sp, color = SonyColors.TextPrimary)
                        val charWidth = remember(style) {
                            measurer.measure("0", style).size.width.toFloat().coerceAtLeast(1f)
                        }
                        // constraints 已排除右侧删除键占位，直接按可用宽度算能放几位
                        val maxChars = (constraints.maxWidth / charWidth).toInt().coerceAtLeast(1)
                        val visible =
                            if (number.length > maxChars) number.takeLast(maxChars) else number
                        Text(
                            text = visible,
                            style = style,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .holdToRepeat(enabled = number.isNotEmpty(), onTrigger = onBackspace),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_sony_backspace),
                        contentDescription = "删除",
                        tint = if (number.isEmpty()) Color(0xFFCCCCCC) else SonyColors.TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // 号码识别结果（归属地左 / 徽章右），高度固定
            VerdictLine(verdict, modifier = Modifier.padding(horizontal = 8.dp))
            // 号码区与键盘之间留足呼吸，否则识别结果一出现就显得挤在一起
            Spacer(Modifier.height(20.dp))
        }

        // ---------- 数字键盘 ----------
        SonyDialPad(
            onDigit = onDigit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        // ---------- 拨号键 ----------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(SonyColors.CallGreen)
                    .then(
                        if (number.isEmpty()) Modifier
                        else Modifier.holdToRepeat(
                            initialDelayMs = 600L,
                            intervalMs = 120L,
                            onTrigger = onDial
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_sony_call_white),
                    contentDescription = "拨号",
                    tint = if (number.isEmpty()) Color.White.copy(alpha = 0.55f) else Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        }

        /*
         * 收起按钮。
         *
         * Sony 原机的拨号页是滑上来的、没有返回键，靠系统返回手势退出。
         * 这里补一个低调的向下箭头：只用系统返回键的话，很多人找不到出口。
         */
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 4.dp, top = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "收起拨号盘",
                tint = SonyColors.TextTertiary,
                modifier = Modifier.size(28.dp)
            )
        }

        // 「添加到联系人」的选人面板，盖在拨号页之上
        if (pickerOpen) {
            AddToContactPanel(
                number = number,
                onDismiss = { pickerOpen = false },
                onPicked = { entry ->
                    pickerOpen = false
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ContactsWriter.addPhone(context, entry.id, number)
                        }
                        toast(
                            context,
                            when (result) {
                                is ContactsWriter.SaveResult.Ok -> "已添加到「${entry.displayName}」"
                                is ContactsWriter.SaveResult.Fail -> result.message
                            }
                        )
                    }
                }
            )
        }
    }
}

/** 拨号页的几个轻提示，不打断输入 */
private fun toast(context: android.content.Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * 陌生号码的快捷操作：新建联系人 / 添加到联系人 / 发送短信。
 *
 * 对齐原机：结果很少时这几行出现在建议列表下方、号码区上方。
 */
@Composable
private fun DialQuickActions(
    onCreate: () -> Unit,
    onAddToExisting: () -> Unit,
    onSendSms: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DialQuickActionRow(R.drawable.ic_sony_person_add, "新建联系人", onCreate)
        DialQuickActionRow(R.drawable.ic_sony_people, "添加到联系人", onAddToExisting)
        DialQuickActionRow(R.drawable.ic_sony_message, "发送短信", onSendSms)
    }
}

@Composable
private fun DialQuickActionRow(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = SonyColors.Theme,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(text = label, fontSize = 15.sp, color = SonyColors.TextPrimary)
    }
}

/** 号码下方的识别结果：归属地左对齐、风险徽章右对齐，高度固定避免号码被顶上去 */
@Composable
private fun VerdictLine(verdict: SpamVerdict?, modifier: Modifier = Modifier) {
    val location = verdict?.location?.display.orEmpty()
    val carrier = verdict?.location?.carrier.orEmpty()
    val sub = listOf(location, carrier).filter { it.isNotBlank() }.joinToString(" · ")
    val label = verdict?.label.orEmpty()

    // 高度恒定：不管有没有识别结果都占同样空间，否则徽章一出现号码就往上跳
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = sub,
            fontSize = 13.sp,
            color = SonyColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (verdict != null && label.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
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
                Text(label, color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

/**
 * T9 搜索建议列表。
 *
 * 号码区上方的弹性空间，点一行直接拨打 —— 用户在这个页面输号码本来就是要打电话，
 * 点结果只把号码填进去等于没完成动作。
 *
 * 高度上限 = [MAX_SUGGESTIONS] 条，超出在列表内部滚动：
 * 列表本身顶部对齐（和原机一致），所以溢出时从顶部开始正向滚动，
 * 第一条永远是排名最高的那一条。上限仍然要控，否则会把号码区挤下去。
 *
 * [query] 参与滚动位置的重置：`LazyListState` 是按 item key 锚定的，两次搜索里
 * 存在同一个联系人（key 相同）时，列表会停在上一次的位置 —— 表现就是
 * 「明明 search 返回的第一条是相晨光，界面却从第六条开始显示」。
 */
@Composable
private fun ContactSuggestions(
    results: List<ContactsRepository.Hit>,
    query: String,
    onPick: (ContactsRepository.Entry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(query) {
        // 换了一次搜索就回到第一条，否则会停在旧滚动位置
        listState.scrollToItem(0)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Top
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SUGGESTION_ROW_HEIGHT * MAX_SUGGESTIONS)
        ) {
            items(results, key = { "${it.entry.id}_${it.entry.number}" }) { hit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SUGGESTION_ROW_HEIGHT)
                        .clickable { onPick(hit.entry) }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PhoneAvatar(
                        name = hit.entry.name,
                        number = hit.entry.number,
                        colorKey = hit.entry.number,
                        size = 38.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = hit.entry.displayName,
                            fontSize = 15.sp,
                            color = SonyColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (hit.entry.name.isNotBlank()) {
                            Spacer(Modifier.height(1.dp))
                            Text(
                                text = hit.entry.number,
                                fontSize = 12.sp,
                                color = SonyColors.TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Text(
                        text = hit.kind.hint(),
                        fontSize = 10.sp,
                        color = SonyColors.TextTertiary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** 匹配方式提示，让用户知道为什么这条会出现 */
private fun ContactsRepository.MatchKind.hint(): String = when (this) {
    ContactsRepository.MatchKind.NUMBER_PREFIX -> "号码"
    ContactsRepository.MatchKind.PINYIN_PREFIX -> "拼音"
    ContactsRepository.MatchKind.INITIALS_PREFIX -> "首字母"
    ContactsRepository.MatchKind.NUMBER_CONTAINS -> "号码"
    ContactsRepository.MatchKind.PINYIN_CONTAINS -> "拼音"
}
