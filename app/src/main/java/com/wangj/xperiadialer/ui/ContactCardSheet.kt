package com.wangj.xperiadialer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wangj.xperiadialer.R
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.wangj.xperiadialer.data.ContactsWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 打开联系人卡片的目标。
 *
 * [contactId] 已知就直接用（通讯录列表里有 id）；通话记录只有号码，
 * 传 null 让卡片自己用 PhoneLookup 反查一次 —— 通话记录里的 cached_name
 * 不可靠，反查才能确认这个号码到底是不是联系人。
 */
data class ContactTarget(
    val name: String,
    val number: String,
    val contactId: Long? = null,
)

/**
 * 联系人卡片 + 编辑表单的宿主。
 *
 * 调用方只需要把「要打开谁」放进 [target]，再传 null 就是关闭。
 *
 * ## 为什么卡片和编辑器不叠加
 *
 * 最初把编辑表单叠在卡片上面（两个弹层同时存在），
 * 实测关闭上面那个会把下面那个一起带走 —— 保存完编辑表单，卡片也没了。
 * 所以改成串行：点编辑 → 先收起卡片 → 再弹表单；保存后重新打开卡片，
 * 让用户直接看到改完的名字。
 */
@Composable
fun ContactCardHost(
    target: ContactTarget?,
    hasWritePermission: Boolean,
    onDismiss: () -> Unit,
    onCall: (String) -> Unit,
    /** 数据被改过（编辑/收藏/删除），调用方据此重载列表 */
    onChanged: () -> Unit,
    onToast: (String) -> Unit,
    onRequestWritePermission: () -> Unit,
) {
    // 外部 target 只是「打开请求」，真正的开关是这个内部状态：
    // 编辑期间需要先把卡片关掉，而外部 target 此时还是非空的。
    var opened by remember { mutableStateOf<ContactTarget?>(null) }

    LaunchedEffect(target) { if (target != null) opened = target }

    opened?.let { current ->
        ContactCardSheet(
            target = current,
            hasWritePermission = hasWritePermission,
            onDismiss = {
                opened = null
                onDismiss()
            },
            onCall = onCall,
            onChanged = onChanged,
            onToast = onToast,
            onRequestWritePermission = onRequestWritePermission,
            onEdit = { request ->
                opened = null
                // 编辑页渲染在 HomeScaffold 的根 Box 里（全屏覆盖层），这里只发请求
                /*
                 * 必须同时把外部的 target 清掉。
                 *
                 * 外部用的是 `mutableStateOf<ContactTarget?>`，ContactTarget 是 data class，
                 * 赋值走结构相等比较。编辑期间如果外部 target 一直留着旧值，
                 * 用户取消编辑后再点**同一行**，赋进去的值和原来的完全相等 →
                 * Compose 认为状态没变，不重组，LaunchedEffect(target) 也不重跑，
                 * opened 依旧是 null，表现就是「点列表没反应」。
                 */
                onDismiss()
                ContactEditorController.open(
                    request = request,
                    onSaved = { msg ->
                        /*
                         * 保存后直接回列表，不再自动重开卡片。
                         *
                         * 重开过一次，很脆：onChanged() 会让列表进入 loading，
                         * ContactsScreen 在 loading 分支里提前 return，
                         * ContactCardHost 整个被卸载，opened 状态跟着丢，
                         * 结果卡片根本弹不出来。回列表还和原机一致。
                         */
                        onChanged()
                        onToast(msg)
                    },
                    onFailed = { msg -> onToast(msg) },
                )
            },
        )
    }

}

/**
 * 自实现联系人的「快捷卡片」，形态对齐原生 QuickContact：
 *
 * ```
 * ┌──────────────────────────────┐
 * │ [68dp头像]  姓名  23sp        │
 * │             号码              │
 * │  (拨号) (短信) (收藏) (编辑)   │
 * │ ────────────────────────────  │
 * │ 📞 号码 · 手机                │  ← 多个号码时逐个列出，点即拨
 * │ ────────────────────────────  │
 * │ 复制号码                      │
 * │ 编辑联系人 / 新建联系人        │
 * │ 删除联系人（红）               │
 * └──────────────────────────────┘
 * ```
 *
 * 容器是**居中的圆角卡片**（`Dialog` + `Surface`），不是底部弹层 ——
 * 原生 QuickContact 就是这种浮在列表上的小卡片，底部弹层反而像"另一页"。
 * 宽度撑满屏幕减去两侧各 20dp，内容超高时卡片内部滚动。
 *
 * 系统那份 QuickContact 在本机启动后立刻自行 finish（见 [ContactActions] 注释），
 * 这里完全自绘，写操作直接落到系统 ContactsProvider，数据和其他 App 共用同一份。
 *
 * ## 为什么要 hasWritePermission 参数
 * READ_CONTACTS 和 WRITE_CONTACTS 是两个独立权限，只授读权限时卡片照样能看，
 * 但点收藏/编辑/删除会抛 SecurityException。这里提前判断，改成引导去授权，
 * 而不是让用户点了没反应。
 */
@Composable
private fun ContactCardSheet(
    target: ContactTarget,
    hasWritePermission: Boolean,
    onDismiss: () -> Unit,
    onCall: (String) -> Unit,
    onChanged: () -> Unit,
    onToast: (String) -> Unit,
    onRequestWritePermission: () -> Unit,
    onEdit: (ContactEditorRequest) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detail by remember(target) { mutableStateOf<ContactsWriter.Detail?>(null) }
    var loading by remember(target) { mutableStateOf(true) }
    // 收藏状态本地先翻，不等 provider 回来，避免点了没反馈
    var starOverride by remember(target) { mutableStateOf<Boolean?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(target) {
        loading = true
        detail = withContext(Dispatchers.IO) {
            val id = target.contactId
                ?: ContactsWriter.loadByNumber(context, target.number)?.contactId
            if (id != null) ContactsWriter.load(context, id) else null
        }
        loading = false
    }

    val phones = detail?.phones.orEmpty()
    val knownName = detail?.name?.takeIf { it.isNotBlank() }
        ?: target.name.takeIf { it.isNotBlank() }
    val displayName = knownName ?: target.number
    val primaryNumber = target.number.ifBlank { phones.firstOrNull()?.value.orEmpty() }
    val starred = starOverride ?: detail?.starred ?: false
    val contactId = detail?.contactId ?: target.contactId

    /** 写操作的统一前置检查 */
    fun requireWrite(block: () -> Unit) {
        if (hasWritePermission) block() else {
            onToast("需要「修改联系人」权限")
            onRequestWritePermission()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        /*
         * usePlatformDefaultWidth = false 让窗口撑满屏幕，好处是卡片能做宽，
         * 代价是「点在卡片外面」不再算 Dialog 外部 —— 整个屏幕都是 Dialog 内容，
         * 系统的 dismissOnClickOutside 永远不触发。
         * 所以这里自己补：外层接点击 = 关闭，卡片本身也挂一个 clickable 把点击吃掉。
         *
         * 用 clickable 而不是 pointerInput + detectTapGestures：后者在父子里都挂上时，
         * 外层照样会被触发（实测点卡片空白处直接把卡片关了）。
         * clickable 的子级优先消费是 Compose 保证的行为，可靠。
         */
        val dismissSource = remember { MutableInteractionSource() }
        val swallowSource = remember { MutableInteractionSource() }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = dismissSource,
                    indication = null
                ) { onDismiss() }
                .padding(horizontal = 20.dp, vertical = 36.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color.White,
                shadowElevation = 22.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .clickable(
                        interactionSource = swallowSource,
                        indication = null
                    ) { /* 吃掉点击，避免冒泡到外层把卡片关掉 */ }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 14.dp)
                ) {
                    // ---------- 头部 ----------
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 26.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DefaultAvatar(size = 78.dp, colorKey = primaryNumber)
                        Spacer(Modifier.width(18.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayName,
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Medium,
                                color = SonyColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (knownName != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = primaryNumber,
                                    fontSize = 14.sp,
                                    color = SonyColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (loading) {
                                Spacer(Modifier.height(3.dp))
                                Text("读取中…", fontSize = 11.sp, color = SonyColors.TextTertiary)
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // ---------- 快捷动作 ----------
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        QuickAction(
                            iconRes = R.drawable.ic_sony_call_white,
                            label = "拨号",
                            tint = SonyColors.CallGreen
                        ) { onCall(primaryNumber) }

                        QuickAction(
                            iconRes = R.drawable.ic_sony_message,
                            label = "短信",
                            tint = SonyColors.Theme
                        ) { ContactActions.sendSms(context, primaryNumber) }

                        /*
                         * 收藏只对已有联系人有意义（陌生号码没有可标星的聚合行）。
                         * loading 期间先占位，否则从通话记录打开时这一格会等详情回来才冒出来，
                         * 整排按钮会横向抖一下。
                         */
                        if (contactId != null || loading) {
                            QuickAction(
                                iconRes = if (starred) R.drawable.ic_sony_star
                                else R.drawable.ic_sony_star_outline,
                                label = if (starred) "已收藏" else "收藏",
                                tint = if (starred) SonyColors.Accent else SonyColors.TextSecondary
                            ) {
                                requireWrite {
                                    val next = !starred
                                    starOverride = next
                                    scope.launch {
                                        val id = contactId
                                        val ok = id != null && withContext(Dispatchers.IO) {
                                            ContactsWriter.setStarred(context, id, next)
                                        }
                                        if (ok) {
                                            onToast(if (next) "已加入常用联系人" else "已取消收藏")
                                            onChanged()
                                        } else {
                                            starOverride = !next
                                            onToast("操作失败")
                                        }
                                    }
                                }
                            }
                        }

                        QuickAction(
                            iconRes = if (contactId != null) R.drawable.ic_sony_edit
                            else R.drawable.ic_sony_person_add,
                            label = if (contactId != null) "编辑" else "新建",
                            tint = SonyColors.Theme
                        ) {
                            requireWrite {
                                onEdit(
                                    ContactEditorRequest(
                                        detail = detail?.takeIf { contactId != null },
                                        initialName = detail?.name ?: target.name,
                                        initialNumber = primaryNumber,
                                    )
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    CardDivider()
                    Spacer(Modifier.height(8.dp))

                    // ---------- 号码列表（只有一个号码时头部已经显示，不重复） ----------
                    if (phones.size > 1) {
                        phones.forEach { phone ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onCall(phone.value) }
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_sony_call_white),
                                    contentDescription = null,
                                    tint = SonyColors.Theme,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = phone.value,
                                        fontSize = 15.sp,
                                        color = SonyColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = phoneTypeLabel(phone.type),
                                        fontSize = 11.sp,
                                        color = SonyColors.TextTertiary
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        CardDivider()
                        Spacer(Modifier.height(6.dp))
                    }

                    // ---------- 操作 ----------
                    CardAction(R.drawable.ic_sony_copy, "复制号码") {
                        ContactActions.copyNumber(context, primaryNumber)
                        onToast("已复制号码")
                    }

                    if (contactId != null) {
                        CardAction(R.drawable.ic_sony_edit, "编辑联系人") {
                            requireWrite {
                                onEdit(
                                    ContactEditorRequest(
                                        detail = detail,
                                        initialName = detail?.name ?: target.name,
                                        initialNumber = primaryNumber,
                                    )
                                )
                            }
                        }
                        CardAction(R.drawable.ic_sony_delete, "删除联系人", danger = true) {
                            requireWrite { confirmDelete = true }
                        }
                    } else {
                        CardAction(R.drawable.ic_sony_person_add, "新建联系人") {
                            requireWrite {
                                onEdit(
                                    ContactEditorRequest(
                                        detail = null,
                                        initialName = target.name,
                                        initialNumber = primaryNumber,
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- 删除确认 ----------
    if (confirmDelete && contactId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Color.White,
            title = { Text("删除联系人", fontSize = 17.sp) },
            text = {
                Text(
                    "将从系统通讯录中删除「$displayName」，该操作不可撤销。",
                    fontSize = 14.sp,
                    color = SonyColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ContactsWriter.delete(context, contactId)
                        }
                        when (result) {
                            is ContactsWriter.SaveResult.Ok -> {
                                onToast("已删除联系人")
                                onChanged()
                                onDismiss()
                            }
                            is ContactsWriter.SaveResult.Fail -> onToast(result.message)
                        }
                    }
                }) {
                    Text("删除", color = SonyColors.EndCallRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消", color = SonyColors.TextSecondary)
                }
            }
        )
    }
}

/** 卡片里的圆形快捷动作：图标 + 下方小字，对齐 QuickContact 那一排按钮 */
@Composable
private fun QuickAction(
    iconRes: Int,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Color(0xFFF2F2F2)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(27.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(text = label, fontSize = 11.5.sp, color = SonyColors.TextSecondary)
    }
}

/** 卡片底部的整行操作 */
@Composable
private fun CardAction(
    iconRes: Int,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 17.dp),
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

/** 电话类型的中文标签（卡片里只展示电话，不涉及邮箱/地址） */
private fun phoneTypeLabel(type: Int): String = when (type) {
    Phone.TYPE_MOBILE -> "手机"
    Phone.TYPE_HOME -> "住宅"
    Phone.TYPE_WORK -> "单位"
    Phone.TYPE_FAX_WORK -> "单位传真"
    Phone.TYPE_FAX_HOME -> "住宅传真"
    Phone.TYPE_PAGER -> "寻呼机"
    else -> "其他"
}

@Composable
private fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 24.dp),
        color = Color(0xFFE8E8E8),
        thickness = 0.5.dp
    )
}
