package com.wangj.xperiadialer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangj.xperiadialer.R
import com.wangj.xperiadialer.data.CallLogRepository
import com.wangj.xperiadialer.data.ContactsRepository
import com.wangj.xperiadialer.data.SpamVerdict

/**
 * 主页容器 —— 结构对齐 Sony 原机 Dialer：
 *
 * ```
 * 顶栏：标题 / 三点菜单
 * 内容：常用联系人 · 通话记录 · 通讯录（默认停在通话记录）
 *                      ↘ 右下角品红 FAB → 打开独立的全屏拨号页
 * 底栏：三个 tab，没有「拨号」
 * ```
 *
 * 原机没有「拨号」tab，拨号是一个悬浮入口 + 独立页面，这里保持一致。
 */
@Composable
fun HomeScaffold(
    // 拨号页
    number: String,
    verdict: SpamVerdict?,
    onDigit: (Char) -> Unit,
    /** 往号码里追加 `,`（2 秒暂停）或 `;`（等待），不触发按键音 */
    onAppendSymbol: (Char) -> Unit,
    onBackspace: () -> Unit,
    /** 拨指定号码：拨号页传当前输入，列表页传点击的那一行 */
    onDial: (String) -> Unit,
    /** 点击 T9 搜索结果：直接拨给该联系人 */
    onPickContact: (ContactsRepository.Entry) -> Unit,
    /** 删除一条通话记录 */
    onDeleteCallLog: (Long) -> Unit,
    // 通话记录
    callLogs: List<CallLogRepository.Entry>,
    callLogLoading: Boolean,
    hasCallLogPermission: Boolean,
    onRequestCallLogPermission: () -> Unit,
    // 通讯录
    contacts: List<ContactsRepository.Entry>,
    contactsLoading: Boolean,
    hasContactsPermission: Boolean,
    onRequestContactsPermission: () -> Unit,
    // 联系人卡片 / 编辑（写通讯录）
    hasWriteContactsPermission: Boolean,
    onRequestWriteContactsPermission: () -> Unit,
    /** 联系人被编辑/删除后刷新列表 */
    onContactsChanged: () -> Unit,
    onToast: (String) -> Unit,
    // 每次从桌面图标进入时递增，用来把 tab 拉回通话记录
    homeResetTick: Int = 0,
    // 菜单
    onOpenPermissions: () -> Unit,
    onPreviewIncoming: () -> Unit,
    onPreviewActive: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    // 原机默认停在通话记录
    var tab by remember { mutableIntStateOf(TAB_CALLLOG) }

    // 从桌面图标再次进入：不管上次停在哪一页，都回到通话记录
    LaunchedEffect(homeResetTick) {
        if (homeResetTick > 0) tab = TAB_CALLLOG
    }
    var menuOpen by remember { mutableStateOf(false) }
    var dialerOpen by remember { mutableStateOf(false) }

    val title = when (tab) {
        TAB_FAVORITES -> "常用联系人"
        TAB_CONTACTS -> "通讯录"
        else -> "通话记录"
    }

    /*
     * T9 搜索建议。
     *
     * 索引在 ContactsRepository.query() 里已经于 IO 线程预热过，这里只剩字符串比较，
     * 几百个联系人也就亚毫秒级，可以直接在组合期算。
     */
    val t9Hits = remember(number, contacts) {
        if (contacts.isEmpty() || number.isEmpty()) {
            emptyList()
        } else {
            ContactsRepository.search(contacts, number)
        }
    }

    val favorites = remember(contacts) { contacts.filter { it.starred } }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SonyColors.Background)
                .statusBarsPadding()
        ) {
            // ---------- 顶部栏 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = SonyColors.TextPrimary
                )
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多",
                            tint = SonyColors.TextSecondary
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = Color.White,
                    ) {
                        DropdownMenuItem(
                            text = { Text("权限设置") },
                            onClick = { menuOpen = false; onOpenPermissions() }
                        )
                        DropdownMenuItem(
                            text = { Text("预览来电界面") },
                            onClick = { menuOpen = false; onPreviewIncoming() }
                        )
                        DropdownMenuItem(
                            text = { Text("预览通话界面") },
                            onClick = { menuOpen = false; onPreviewActive() }
                        )
                        DropdownMenuItem(
                            text = { Text("调试诊断") },
                            onClick = { menuOpen = false; onOpenDiagnostics() }
                        )
                    }
                }
            }

            // ---------- 内容区 ----------
            Box(modifier = Modifier.weight(1f)) {
                /*
                 * 列表交互分层（对齐 Sony 原机）：
                 * - 点头像 → 系统快捷联系人卡片
                 * - 点行   → 就地展开「发送短信 / 通话详情」
                 * - 点电话图标 → 直接拨号
                 * 行本身不再绑拨号，误触代价太大。
                 */
                when (tab) {
                    TAB_FAVORITES -> ContactsScreen(
                        contacts = favorites,
                        hasPermission = hasContactsPermission,
                        loading = contactsLoading,
                        onRequestPermission = onRequestContactsPermission,
                        onCall = onDial,
                        showLetterHeader = false,
                        emptyTitle = "还没有常用联系人",
                        emptyDesc = "在系统通讯录里给联系人加星标，就会出现在这里",
                        hasWritePermission = hasWriteContactsPermission,
                        onRequestWritePermission = onRequestWriteContactsPermission,
                        onDataChanged = onContactsChanged,
                        onToast = onToast,
                    )

                    TAB_CONTACTS -> ContactsScreen(
                        contacts = contacts,
                        hasPermission = hasContactsPermission,
                        loading = contactsLoading,
                        onRequestPermission = onRequestContactsPermission,
                        onCall = onDial,
                        hasWritePermission = hasWriteContactsPermission,
                        onRequestWritePermission = onRequestWriteContactsPermission,
                        onDataChanged = onContactsChanged,
                        onToast = onToast,
                        onCreateContact = {
                            if (!hasWriteContactsPermission) {
                                onRequestWriteContactsPermission()
                            } else {
                                ContactEditorController.open(
                                    request = ContactEditorRequest(detail = null),
                                    onSaved = { msg ->
                                        onToast(msg)
                                        onContactsChanged()
                                    },
                                    onFailed = { msg -> onToast(msg) },
                                )
                            }
                        },
                    )

                    else -> CallLogScreen(
                        entries = callLogs,
                        hasPermission = hasCallLogPermission,
                        loading = callLogLoading,
                        onRequestPermission = onRequestCallLogPermission,
                        onCall = onDial,
                        onDelete = onDeleteCallLog,
                        hasWritePermission = hasWriteContactsPermission,
                        onRequestWritePermission = onRequestWriteContactsPermission,
                        onDataChanged = onContactsChanged,
                        onToast = onToast,
                    )
                }

                // 右下角悬浮拨号键 —— 原机的拨号入口
                DialFab(
                    onClick = { dialerOpen = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 18.dp)
                )
            }

            // ---------- 底部导航 ----------
            HorizontalDivider(color = Color(0xFFE8E8E8), thickness = 0.5.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 顺序很重要：先 navigationBarsPadding 让出系统导航栏，再定高度。
                    // 反过来写（height 在前）会把 58dp 内部再扣掉 inset，图标被压掉一半。
                    .navigationBarsPadding()
                    .height(58.dp)
            ) {
                BottomTab(
                    label = "常用联系人",
                    icon = painterResource(R.drawable.ic_sony_star),
                    selected = tab == TAB_FAVORITES,
                    modifier = Modifier.weight(1f)
                ) { tab = TAB_FAVORITES }
                BottomTab(
                    label = "通话记录",
                    icon = painterResource(R.drawable.ic_sony_history),
                    selected = tab == TAB_CALLLOG,
                    modifier = Modifier.weight(1f)
                ) { tab = TAB_CALLLOG }
                BottomTab(
                    label = "通讯录",
                    icon = painterResource(R.drawable.ic_sony_people),
                    selected = tab == TAB_CONTACTS,
                    modifier = Modifier.weight(1f)
                ) { tab = TAB_CONTACTS }
            }
        }

        // ---------- 拨号页（全屏覆盖） ----------
        if (dialerOpen) {
            DialerHome(
                number = number,
                verdict = verdict,
                results = t9Hits,
                onDigit = onDigit,
                onAppendSymbol = onAppendSymbol,
                onBackspace = onBackspace,
                onDial = { onDial(number) },
                onPickContact = onPickContact,
                onClose = { dialerOpen = false }
            )
        }

        /*
         * ---------- 联系人编辑页（全屏覆盖） ----------
         *
         * 在这里渲染而不是在联系人卡片内部：卡片在 ContactsScreen / CallLogScreen 里，
         * 那些内容被 weight(1f) 的容器约束着，塞不下全屏页。放在根 Box 里，
         * 它和列表页同属 MainActivity 的 window，insets 天然有效，
         * 不必像 Dialog 那样从外面传导航栏高度进来。
         */
        ContactEditorController.request?.let { request ->
            ContactEditorPanel(
                request = request,
                onDismiss = { ContactEditorController.close() },
                onSaved = { msg -> ContactEditorController.notifySaved(msg) },
                onFailed = { msg -> ContactEditorController.notifyFailed(msg) },
            )
        }
    }
}

private const val TAB_FAVORITES = 0
private const val TAB_CALLLOG = 1
private const val TAB_CONTACTS = 2

/** 悬浮拨号键：品红圆 + 九宫格图标，和原机一致 */
@Composable
private fun DialFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(SonyColors.Accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_sony_dialpad),
            contentDescription = "拨号盘",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun BottomTab(
    label: String,
    icon: Painter,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val color = if (selected) SonyColors.Theme else SonyColors.TextTertiary
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(text = label, fontSize = 10.sp, color = color)
    }
}
