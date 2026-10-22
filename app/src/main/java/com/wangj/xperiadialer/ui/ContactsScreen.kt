package com.wangj.xperiadialer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangj.xperiadialer.data.ContactsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 通讯录列表。
 *
 * 按拼音首字母分组（依赖系统给的 SORT_KEY_PRIMARY），A-Z 顺序，非字母归到 "#"。
 * 每行：彩色字母头像 + 姓名 + 号码。
 *
 * ## 交互（对齐 Sony 原机通讯录）
 * ```
 * 点头像   → 联系人卡片（拨号 / 短信 / 收藏 / 编辑）
 * 点信息区 → 就地展开：拨打电话 / 发送短信 / 联系人卡片 / 复制号码
 * 右侧边缘 → 隐藏的快速拖动条，拖动时中央弹出拼音首字母
 * ```
 * 列表**没有**右侧拨打按钮 —— 原机也没有，误触代价太大（一次点击就拨出去）。
 * 拨号入口收在卡片和展开区里。
 *
 * 「常用联系人」复用同一个列表，但关掉字母分组与快速拖动（收藏通常只有几个人）。
 */
@Composable
fun ContactsScreen(
    contacts: List<ContactsRepository.Entry>,
    hasPermission: Boolean,
    loading: Boolean,
    onRequestPermission: () -> Unit,
    onCall: (String) -> Unit,
    showLetterHeader: Boolean = true,
    emptyTitle: String = "通讯录为空",
    emptyDesc: String = "",
    // 联系人卡片 / 编辑
    hasWritePermission: Boolean = false,
    onRequestWritePermission: () -> Unit = {},
    onDataChanged: () -> Unit = {},
    onToast: (String) -> Unit = {},
) {
    if (!hasPermission) {
        ContactsHint(
            title = "无法读取通讯录",
            desc = "需要「联系人」权限才能显示",
            actionText = "去授权",
            onAction = onRequestPermission
        )
        return
    }
    if (loading) {
        ContactsHint(title = "正在读取…", desc = "", actionText = null, onAction = {})
        return
    }
    if (contacts.isEmpty()) {
        ContactsHint(title = emptyTitle, desc = emptyDesc, actionText = null, onAction = {})
        return
    }

    val groups = contacts.groupBy { it.initial }
        .toSortedMap(compareBy { if (it == "#") "ZZZ" else it })

    /*
     * 每个字母分组在 LazyColumn 里的起始 item index。
     *
     * 快速拖动只按字母个数等分整条轨道（不是按内容长度），所以这里必须精确算出
     * 「第 n 个字母的第一个 item 是第几个」，否则拖到 M 会落到 L 或 N 上。
     * 有字母表头时每个分组先占 1 个 header，再是 list.size 个联系人。
     */
    val sections = remember(groups, showLetterHeader) {
        var cursor = 0
        groups.map { (initial, list) ->
            val start = cursor
            if (showLetterHeader) cursor += 1
            cursor += list.size
            Section(initial, start)
        }
    }

    // 当前打开联系人卡片的联系人
    var cardTarget by remember { mutableStateOf<ContactTarget?>(null) }

    // ---------- 快速拖动状态 ----------
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var activeSection by remember { mutableIntStateOf(0) }
    var bubbleVisible by remember { mutableStateOf(false) }

    // 松手后气泡再停留一会儿，太早消失会看不清拖到了哪个字母
    LaunchedEffect(dragging) {
        if (dragging) {
            bubbleVisible = true
        } else {
            delay(420)
            bubbleVisible = false
        }
    }

    /*
     * 分组数多于 3 才值得给拖动条 —— 两三个字母的列表一屏就看完了。
     * 常用联系人页关了字母分组，自然也不会出现。
     */
    val fastScrollEnabled = showLetterHeader && sections.size > 3

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            groups.forEach { (initial, list) ->
                if (showLetterHeader) {
                    item(key = "h_$initial") {
                        Text(
                            text = initial,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = SonyColors.Theme,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SonyColors.Background)
                                .padding(start = 20.dp, top = 10.dp, bottom = 4.dp)
                        )
                    }
                }
                items(list, key = { "ct_${it.id}_${it.number}" }) { contact ->
                    /*
                     * 整行一个点击区：头像和文字都是同一个入口，打开联系人卡片。
                     *
                     * 之前这里是「头像开卡片 / 信息区展开操作」两套行为，还因为嵌套
                     * clickable 被外层抢占踩过坑。现在行为统一了，只有一个 clickable，
                     * 也就没有抢占问题 —— 行内不要再给子元素加 clickable。
                     */
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                cardTarget = ContactTarget(
                                    name = contact.name,
                                    number = contact.number,
                                    contactId = contact.id,
                                )
                            }
                            .padding(start = 20.dp, end = 26.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LetterAvatar(
                            name = contact.name,
                            number = contact.number,
                            colorKey = contact.number,
                            size = 44.dp
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = contact.displayName,
                                fontSize = 16.sp,
                                color = SonyColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (contact.name.isNotBlank() && contact.number.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = contact.number,
                                    fontSize = 12.sp,
                                    color = SonyColors.TextTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------- 右侧隐藏快速拖动条 ----------
        if (fastScrollEnabled) {
            FastScrollBar(
                count = sections.size,
                activeIndex = activeSection,
                dragging = dragging,
                onPress = { dragging = true },
                onRelease = { dragging = false },
                onPick = { index ->
                    activeSection = index
                    // 拖动中连续 cancel + relaunch：scrollToItem 是瞬时定位，
                    // 不 cancel 会在快速拖动时堆一串协程
                    scrollJob?.cancel()
                    scrollJob = scope.launch {
                        listState.scrollToItem(sections[index].startIndex)
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(SCROLL_BAR_HIT_WIDTH)
            )
        }

        // ---------- 拖动时的首字母提示 ----------
        AnimatedVisibility(
            visible = bubbleVisible && sections.isNotEmpty(),
            enter = fadeIn() + scaleIn(initialScale = 0.82f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(Color(0xF02A2A2A)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = sections.getOrNull(activeSection)?.letter.orEmpty(),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }
    }

    // 联系人卡片（自研，替代在本机不可用的系统 QuickContact）
    ContactCardHost(
        target = cardTarget,
        hasWritePermission = hasWritePermission,
        onDismiss = { cardTarget = null },
        onCall = onCall,
        onChanged = onDataChanged,
        onToast = onToast,
        onRequestWritePermission = onRequestWritePermission,
    )
}

/** 一个字母分组在 LazyColumn 中的起始位置 */
private data class Section(val letter: String, val startIndex: Int)

/** 右侧拖动轨道的触摸宽度：足够好按，又不至于挡住列表内容 */
private val SCROLL_BAR_HIT_WIDTH = 30.dp

/**
 * 隐藏的快速拖动条。
 *
 * 平时完全不可见 —— 只有按下去才画出当前位置的圆角短条。
 * 轨道按**字母个数等分**，拖到哪个字母就跳到那个分组的第一个 item，
 * 这是通讯录索引条的标准做法（按内容长度算位置会拖不准）。
 */
@Composable
private fun FastScrollBar(
    count: Int,
    activeIndex: Int,
    dragging: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .drawBehind {
                if (!dragging || count <= 0) return@drawBehind
                val segH = size.height / count
                val top = segH * activeIndex.coerceIn(0, count - 1)
                val barW = 5.dp.toPx()
                drawRoundRect(
                    color = Color(0x592A56C6),
                    topLeft = Offset(size.width - barW - 5.dp.toPx(), top + segH * 0.1f),
                    size = Size(barW, segH * 0.8f),
                    cornerRadius = CornerRadius(barW / 2, barW / 2)
                )
            }
            .pointerInput(count) {
                if (count <= 0) return@pointerInput
                val h = size.height.toFloat()
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onPress()
                        onPick(indexAt(down.position.y, h, count))
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                pressed = false
                            } else {
                                pressed = change.pressed
                                if (pressed) {
                                    change.consume()
                                    onPick(indexAt(change.position.y, h, count))
                                }
                            }
                        }
                        onRelease()
                    }
                }
            }
    )
}

/** 手指 y 落在第几个字母格子上 */
private fun indexAt(y: Float, height: Float, count: Int): Int {
    if (height <= 0f) return 0
    val ratio = (y / height).coerceIn(0f, 0.9999f)
    return (ratio * count).toInt().coerceIn(0, count - 1)
}

@Composable
private fun ContactsHint(
    title: String,
    desc: String,
    actionText: String?,
    onAction: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, fontSize = 15.sp, color = SonyColors.TextSecondary)
            if (desc.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(desc, fontSize = 12.sp, color = SonyColors.TextTertiary)
            }
            if (actionText != null) {
                Spacer(Modifier.height(14.dp))
                Button(onClick = onAction) { Text(actionText, fontSize = 14.sp) }
            }
        }
    }
}
