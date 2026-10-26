package com.wangj.xperiadialer.ui

import androidx.annotation.DrawableRes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangj.xperiadialer.R
import com.wangj.xperiadialer.data.CallLogRepository
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 通话记录列表。
 *
 * 按日期分组（今天 / 昨天 / 具体日期），组内按时间倒序。
 * 每行结构与 Sony Dialer 一致：彩色字母头像 → 姓名 / 号码 → 类型 + 归属地 + 时间 → 回拨按钮。
 */
@Composable
fun CallLogScreen(
    entries: List<CallLogRepository.Entry>,
    hasPermission: Boolean,
    loading: Boolean,
    onRequestPermission: () -> Unit,
    onCall: (String) -> Unit,
    /** 删除一条通话记录（来自「通话详情」弹窗） */
    onDelete: (Long) -> Unit,
    // 联系人卡片 / 编辑
    hasWritePermission: Boolean = false,
    onRequestWritePermission: () -> Unit = {},
    onDataChanged: () -> Unit = {},
    onToast: (String) -> Unit = {},
) {
    if (!hasPermission) {
        EmptyHint(
            title = "无法读取通话记录",
            desc = "需要「通话记录」权限才能显示",
            actionText = "去授权",
            onAction = onRequestPermission
        )
        return
    }
    if (loading) {
        EmptyHint(title = "正在读取…", desc = "", actionText = null, onAction = {})
        return
    }
    if (entries.isEmpty()) {
        EmptyHint(title = "暂无通话记录", desc = "", actionText = null, onAction = {})
        return
    }

    // 按日期切成若干组
    val groups = groupByDate(entries)

    // 当前展开操作区的行 id（同时只展开一行，和原机一致）
    var expandedId by remember { mutableStateOf<Long?>(null) }
    // 「通话详情」要看的记录
    var detailEntry by remember { mutableStateOf<CallLogRepository.Entry?>(null) }
    // 当前打开联系人卡片的目标
    var cardTarget by remember { mutableStateOf<ContactTarget?>(null) }

    val context = LocalContext.current

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        groups.forEach { (label, items) ->
            item(key = "header_$label") {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = SonyColors.TextSecondary,
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp)
                )
            }
            items(items, key = { "call_${it.id}" }) { entry ->
                Column {
                    CallLogRow(
                        entry = entry,
                        onCall = { onCall(entry.number) },
                        /*
                         * 头像：打开自研联系人卡片。
                         * 只有号码，contactId 传 null 让卡片自己用 PhoneLookup 反查 ——
                         * 通话记录的 cached_name 不可靠，反查才能确认是不是联系人。
                         */
                        onAvatarClick = {
                            cardTarget = ContactTarget(
                                name = entry.name,
                                number = entry.number,
                                contactId = null,
                            )
                        },
                        onRowClick = {
                            expandedId = if (expandedId == entry.id) null else entry.id
                        }
                    )
                    if (expandedId == entry.id) {
                        ActionRow(
                            iconRes = R.drawable.ic_sony_message,
                            label = "发送短信"
                        ) { ContactActions.sendSms(context, entry.number) }
                        ActionRow(
                            iconRes = R.drawable.ic_sony_info,
                            label = "通话详情"
                        ) { detailEntry = entry }
                    }
                }
            }
        }
    }

    detailEntry?.let { entry ->
        CallDetailSheet(
            entry = entry,
            onDismiss = { detailEntry = null },
            onCall = { onCall(entry.number); detailEntry = null },
            onDelete = { onDelete(entry.id); detailEntry = null }
        )
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

/** 展开后的操作项：图标 + 文字，缩进到姓名位置 */
@Composable
private fun ActionRow(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 78.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = SonyColors.TextSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(18.dp))
        Text(text = label, fontSize = 15.sp, color = SonyColors.TextPrimary)
    }
}

@Composable
private fun CallLogRow(
    entry: CallLogRepository.Entry,
    onCall: () -> Unit,
    onAvatarClick: () -> Unit,
    onRowClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        /*
         * 三个点击区必须平级，不能嵌套：外层 Row 一旦带 clickable，
         * 内层头像的 clickable 会被父级抢先（实测点了头像只会展开操作区）。
         */
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onAvatarClick)
        ) {
            PhoneAvatar(
                name = entry.name,
                number = entry.number,
                colorKey = entry.number,
                size = 44.dp
            )
        }
        Spacer(Modifier.width(14.dp))

        // 信息区：姓名 + 类型/归属地 + 时间，点这里展开操作
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onRowClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    fontSize = 16.sp,
                    color = if (entry.isMissed) SonyColors.Red else SonyColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = typeLabel(entry.type),
                        fontSize = 12.sp,
                        color = if (entry.isMissed) SonyColors.Red else SonyColors.TextTertiary
                    )
                    val sub = listOf(
                        entry.location.takeIf { it.isNotBlank() },
                        if (entry.durationSec > 0) formatDuration(entry.durationSec) else null
                    ).filterNotNull().joinToString(" · ")
                    if (sub.isNotBlank()) {
                        Text(
                            text = " · $sub",
                            fontSize = 12.sp,
                            color = SonyColors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatTime(entry.dateMillis),
                fontSize = 12.sp,
                color = SonyColors.TextTertiary
            )
        }

        Spacer(Modifier.width(12.dp))

        // 呼叫按钮：唯一会真正拨出去的地方
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0xFFF0F0F0))
                .clickable(onClick = onCall),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_sony_call_white),
                contentDescription = "回拨",
                modifier = Modifier.size(18.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(SonyColors.Theme)
            )
        }
    }
}

@Composable
private fun EmptyHint(
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

private fun typeLabel(type: Int): String = when (type) {
    CallLogRepository.TYPE_INCOMING -> "↙ 来电"
    CallLogRepository.TYPE_OUTGOING -> "↗ 去电"
    CallLogRepository.TYPE_MISSED -> "✖ 未接"
    CallLogRepository.TYPE_REJECTED -> "✖ 已拒接"
    CallLogRepository.TYPE_BLOCKED -> "✖ 已拦截"
    else -> "· 通话"
}

private fun formatDuration(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return if (m > 0) "${m}分${s}秒" else "${s}秒"
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateFmt = SimpleDateFormat("M月d日", Locale.getDefault())
private val yearFmt = SimpleDateFormat("yyyy/M/d", Locale.getDefault())

private fun formatTime(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val today = now.get(Calendar.YEAR) to now.get(Calendar.DAY_OF_YEAR)
    val target = then.get(Calendar.YEAR) to then.get(Calendar.DAY_OF_YEAR)
    return when {
        today == target -> timeFmt.format(Date(millis))
        today.first == then.get(Calendar.YEAR) -> dateFmt.format(Date(millis))
        else -> yearFmt.format(Date(millis))
    }
}

/** 按「今天 / 昨天 / 日期」分组，保持原有倒序 */
private fun groupByDate(
    entries: List<CallLogRepository.Entry>
): List<Pair<String, List<CallLogRepository.Entry>>> {
    val now = Calendar.getInstance()
    val todayKey = now.get(Calendar.YEAR) to now.get(Calendar.DAY_OF_YEAR)
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val yesterdayKey = yesterday.get(Calendar.YEAR) to yesterday.get(Calendar.DAY_OF_YEAR)

    val result = LinkedHashMap<String, MutableList<CallLogRepository.Entry>>()
    entries.forEach { e ->
        val cal = Calendar.getInstance().apply { timeInMillis = e.dateMillis }
        val key = cal.get(Calendar.YEAR) to cal.get(Calendar.DAY_OF_YEAR)
        val label = when (key) {
            todayKey -> "今天"
            yesterdayKey -> "昨天"
            else -> if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
                dateFmt.format(Date(e.dateMillis))
            } else {
                yearFmt.format(Date(e.dateMillis))
            }
        }
        result.getOrPut(label) { mutableListOf() }.add(e)
    }
    return result.map { it.key to it.value.toList() }
}
