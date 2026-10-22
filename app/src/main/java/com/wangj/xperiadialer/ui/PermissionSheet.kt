package com.wangj.xperiadialer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 权限与角色设置。
 *
 * 这些是拨号器能正常工作的前提，全部集中在一处，状态实时反映系统返回值。
 * 任何一项没开都可能导致「来电不响铃」「界面不弹」这类看起来像 bug 的现象。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(
    isDialer: Boolean,
    isScreening: Boolean,
    hasNotify: Boolean,
    canFsi: Boolean,
    permsOk: Boolean,
    onDismiss: () -> Unit,
    onRequestDialer: () -> Unit,
    onRequestScreening: () -> Unit,
    onRequestNotify: () -> Unit,
    onRequestFsi: () -> Unit,
    onRequestPerms: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SonyColors.Background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "权限设置",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = SonyColors.TextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "缺少任意一项都可能表现为「来电不响铃 / 界面不弹出」",
                fontSize = 12.sp,
                color = SonyColors.TextSecondary
            )
            Spacer(Modifier.height(16.dp))

            PermissionItem(
                title = "默认拨号应用",
                desc = "接听来电、发起通话、绘制通话界面必需",
                ok = isDialer,
                actionText = "去设置",
                onAction = onRequestDialer,
            )
            PermissionItem(
                title = "来电筛选",
                desc = "拦截骚扰号码并在来电界面显示标记",
                ok = isScreening,
                actionText = "去设置",
                onAction = onRequestScreening,
            )
            PermissionItem(
                title = "通知权限",
                desc = "来电通知与通话中常驻通知",
                ok = hasNotify,
                actionText = "去授权",
                onAction = onRequestNotify,
            )
            PermissionItem(
                title = "全屏来电通知",
                desc = "Android 14+ 锁屏时才能全屏弹出来电界面",
                ok = canFsi,
                actionText = "去设置",
                onAction = onRequestFsi,
            )
            PermissionItem(
                title = "基础权限",
                desc = "电话、联系人、通话记录、通知",
                ok = permsOk,
                actionText = "去授权",
                onAction = onRequestPerms,
            )

            Spacer(Modifier.height(12.dp))
            val allOk = isDialer && isScreening && hasNotify && canFsi && permsOk
            if (allOk) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(SonyColors.CallGreen)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("全部就绪", fontSize = 13.sp, color = SonyColors.CallGreen)
                }
            } else {
                Button(
                    onClick = {
                        onRequestDialer()
                        onRequestScreening()
                        onRequestPerms()
                        onRequestNotify()
                        onRequestFsi()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("逐项申请")
                }
            }
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    desc: String,
    ok: Boolean,
    actionText: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (ok) SonyColors.CallGreen else Color(0xFFE0E0E0))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = SonyColors.TextPrimary
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = desc,
                fontSize = 12.sp,
                color = SonyColors.TextSecondary,
                modifier = Modifier.padding(start = 15.dp)
            )
        }
        if (ok) {
            Text("已开启", fontSize = 13.sp, color = SonyColors.CallGreen)
        } else {
            OutlinedButton(onClick = onAction, contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 14.dp, vertical = 4.dp
            )) {
                Text(actionText, fontSize = 13.sp)
            }
        }
    }
}
