package com.wangj.xperiadialer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangj.xperiadialer.R
import com.wangj.xperiadialer.data.ContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「添加到联系人」的选人面板。
 *
 * 拨号页输了一个陌生号码时，把它挂到已有联系人身上。
 *
 * 做成 Activity 内的全屏覆盖层（和 `ContactEditorPanel` 一样），不用 `Dialog`：
 * Dialog 是独立 window，Compose 在里面读到的 insets 全是 0，
 * 搜索框一弹键盘就没法靠 `imePadding()` 让开，列表会被输入法盖住。
 * 覆盖层用 `windowInsetsPadding(statusBars ∪ ime ∪ navigationBars)` 一次处理干净。
 */
@Composable
fun AddToContactPanel(
    number: String,
    onDismiss: () -> Unit,
    onPicked: (ContactsRepository.Entry) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<ContactsRepository.Entry>?>(null) }

    BackHandler(onBack = onDismiss)

    // 通讯录取一次就够，之后的过滤都在内存里做
    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) { ContactsRepository.query(context) }
    }

    val filtered = remember(all, query) {
        val list = all.orEmpty()
        val q = query.trim()
        if (q.isEmpty()) {
            list
        } else {
            val digits = q.filter { it.isDigit() }
            list.filter { entry ->
                entry.name.contains(q, ignoreCase = true) ||
                    entry.number.contains(q) ||
                    (digits.isNotEmpty() && entry.digits.contains(digits))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            // 吃掉下层拨号页的点击，避免面板打开时误触键盘
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.statusBars.union(WindowInsets.ime).union(WindowInsets.navigationBars)
                )
        ) {
            // ---------- 顶栏 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", fontSize = 20.sp, color = SonyColors.TextSecondary)
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "添加到联系人",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = SonyColors.TextPrimary
                    )
                    Text(
                        text = number,
                        fontSize = 12.sp,
                        color = SonyColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ---------- 搜索 ----------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF2F2F2))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (query.isEmpty()) {
                    Text("搜索姓名或号码", fontSize = 15.sp, color = SonyColors.TextTertiary)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = SonyColors.TextPrimary),
                    cursorBrush = SolidColor(SonyColors.Theme),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))

            // ---------- 联系人列表 ----------
            when {
                all == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("加载中…", fontSize = 14.sp, color = SonyColors.TextTertiary)
                }

                filtered.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("没有匹配的联系人", fontSize = 14.sp, color = SonyColors.TextTertiary)
                }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { "${it.id}_${it.number}" }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPicked(entry) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            LetterAvatar(
                                name = entry.name,
                                number = entry.number,
                                colorKey = entry.number,
                                size = 40.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.displayName,
                                    fontSize = 16.sp,
                                    color = SonyColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (entry.name.isNotBlank() && entry.number.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = entry.number,
                                        fontSize = 12.sp,
                                        color = SonyColors.TextTertiary,
                                        maxLines = 1
                                    )
                                }
                            }
                            Icon(
                                painter = painterResource(R.drawable.ic_sony_person_add),
                                contentDescription = null,
                                tint = SonyColors.Theme,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
