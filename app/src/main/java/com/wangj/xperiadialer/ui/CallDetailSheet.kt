package com.wangj.xperiadialer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangj.xperiadialer.R
import com.wangj.xperiadialer.data.CallLogRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「通话详情」底部弹窗。
 *
 * 对应原机点行展开后点「通话详情」打开的 OldCallDetailsActivity：
 * 上半部分是联系人 + 这一路的记录，下半部分是复制号码 / 删除。
 *
 * 原机还有「拨打电话之前修改号码」，这里改成点击号码本身回到拨号页编辑，
 * 少一层入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailSheet(
    entry: CallLogRepository.Entry,
    onDismiss: () -> Unit,
    onCall: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp)
        ) {
            // ---------- 头部：头像 + 姓名 + 号码 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LetterAvatar(
                    name = entry.name,
                    number = entry.number,
                    colorKey = entry.number,
                    size = 52.dp
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = SonyColors.TextPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = entry.number,
                        fontSize = 13.sp,
                        color = SonyColors.TextSecondary
                    )
                }
                Row(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(onClick = onCall),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_sony_call_white),
                        contentDescription = "拨打",
                        tint = SonyColors.CallGreen,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // ---------- 这一路的记录 ----------
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = "${detailTypeLabel(entry.type)} · ${detailDurationLabel(entry)}",
                    fontSize = 15.sp,
                    color = SonyColors.TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(fullTime(entry.dateMillis))
                        if (entry.location.isNotBlank()) append("  ${entry.location}")
                    },
                    fontSize = 12.sp,
                    color = SonyColors.TextTertiary
                )
            }

            Spacer(Modifier.height(16.dp))
            SheetDivider()
            Spacer(Modifier.height(4.dp))

            // ---------- 操作 ----------
            DetailAction(R.drawable.ic_sony_copy, "复制号码") {
                ContactActions.copyNumber(context, entry.number)
                onDismiss()
            }
            DetailAction(R.drawable.ic_sony_delete, "删除这条记录", danger = true) {
                onDelete()
            }
        }
    }
}

@Composable
private fun DetailAction(
    iconRes: Int,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (danger) SonyColors.EndCallRed else SonyColors.TextSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = label,
            fontSize = 15.sp,
            color = if (danger) SonyColors.EndCallRed else SonyColors.TextPrimary
        )
    }
}

@Composable
private fun SheetDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(horizontal = 24.dp),
        color = Color(0xFFE8E8E8),
        thickness = 0.5.dp
    )
}

private fun detailTypeLabel(type: Int): String = when (type) {
    CallLogRepository.TYPE_INCOMING -> "来电"
    CallLogRepository.TYPE_OUTGOING -> "去电"
    CallLogRepository.TYPE_MISSED -> "未接来电"
    CallLogRepository.TYPE_REJECTED -> "已拒接"
    else -> "通话"
}

private fun detailDurationLabel(entry: CallLogRepository.Entry): String =
    if (entry.durationSec > 0) "${entry.durationSec} 秒" else "未接通"

private fun fullTime(millis: Long): String =
    SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(Date(millis))
