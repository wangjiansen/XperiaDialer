package com.wangj.xperiadialer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangj.xperiadialer.data.ContactsWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website

/**
 * 编辑请求。
 *
 * [detail] 为 null 表示新建 —— 这时 [initialName] / [initialNumber] 可能来自
 * 通话记录里的陌生号码，预填进表单省一次输入。
 */
data class ContactEditorRequest(
    val detail: ContactsWriter.Detail?,
    val initialName: String = "",
    val initialNumber: String = "",
)

// ------------------------------------------------------------------ 下拉选项

private val PHONE_TYPES = listOf(
    Phone.TYPE_MOBILE to "手机",
    Phone.TYPE_HOME to "住宅",
    Phone.TYPE_WORK to "单位",
    Phone.TYPE_FAX_WORK to "单位传真",
    Phone.TYPE_FAX_HOME to "住宅传真",
    Phone.TYPE_OTHER to "其他",
)

private val EMAIL_TYPES = listOf(
    Email.TYPE_HOME to "个人",
    Email.TYPE_WORK to "工作",
    Email.TYPE_OTHER to "其他",
)

private val POSTAL_TYPES = listOf(
    StructuredPostal.TYPE_HOME to "住宅",
    StructuredPostal.TYPE_WORK to "单位",
    StructuredPostal.TYPE_OTHER to "其他",
)

private val WEBSITE_TYPES = listOf(
    Website.TYPE_HOMEPAGE to "主页",
    Website.TYPE_WORK to "工作",
    Website.TYPE_OTHER to "其他",
)

/**
 * 联系人编辑页（新建 / 修改共用）。
 *
 * 对齐原机通讯录的编辑页：**全屏**、顶栏右上角保存、字段按组排列，
 * 电话 / 邮箱 / 地址 / 网站都可以任意增行删行。
 *
 * 原机还有「姓氏 / 名字 / 姓氏拼音 / 中间名拼音 / 名字拼音」这些拆分字段，
 * 这里只保留一个「姓名」：中文场景下系统通讯录也是把整串名字塞进 given name，
 * 拼音由输入法/系统排序键负责，暴露出来只会让人多填两遍。
 * 备注是**多行文本框**，回车正常换行，不会被当成确认。
 *
 * 全屏 Dialog 而不是底部弹层：字段一多，底部弹层要么撑到顶要么滚成一条缝，
 * 而且键盘一弹就只剩半屏 —— 原机也是这么做的。
 */
@Composable
fun ContactEditorPanel(
    request: ContactEditorRequest,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
    onFailed: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val detail = request.detail
    val isNew = detail == null

    var name by remember(request) {
        mutableStateOf(detail?.name?.takeIf { it.isNotBlank() } ?: request.initialName)
    }
    var nickname by remember(request) { mutableStateOf(detail?.nickname.orEmpty()) }
    var company by remember(request) { mutableStateOf(detail?.company.orEmpty()) }
    var department by remember(request) { mutableStateOf(detail?.department.orEmpty()) }
    var jobTitle by remember(request) { mutableStateOf(detail?.jobTitle.orEmpty()) }
    var website by remember(request) {
        mutableStateOf(detail?.websites?.firstOrNull()?.value.orEmpty())
    }
    var birthday by remember(request) { mutableStateOf(detail?.birthday.orEmpty()) }
    var note by remember(request) { mutableStateOf(detail?.note.orEmpty()) }
    var starred by remember(request) { mutableStateOf(detail?.starred ?: false) }
    var saving by remember(request) { mutableStateOf(false) }

    /*
     * 常用字段默认只显示「姓名 / 电话 / 邮箱」，其余收进「更多字段」——
     * 和原机编辑页一致（它也是先给精简表单，点「更多字段」才铺开）。
     *
     * 但编辑已有联系人时，如果折叠区里有数据就必须默认展开，
     * 否则用户看不到公司、备注这些已经填过的内容，还以为是丢了。
     */
    var showMore by remember(request) {
        mutableStateOf(
            detail != null && (
                detail.nickname.isNotBlank() || detail.company.isNotBlank() ||
                    detail.department.isNotBlank() || detail.jobTitle.isNotBlank() ||
                    detail.websites.isNotEmpty() || detail.birthday.isNotBlank() ||
                    detail.note.isNotBlank() || detail.addresses.isNotEmpty()
                )
        )
    }

    // 覆盖层没有 Dialog 的返回键处理，自己接管
    androidx.activity.compose.BackHandler(enabled = true) {
        if (!saving) onDismiss()
    }

    val phoneRows = remember(request) {
        mutableStateListOf<ContactsWriter.DraftItem>().apply {
            val init = detail?.phones?.map { ContactsWriter.DraftItem(it.value, it.type) }
            if (!init.isNullOrEmpty()) addAll(init)
            else add(
                ContactsWriter.DraftItem(
                    request.initialNumber,
                    Phone.TYPE_MOBILE
                )
            )
        }
    }
    val emailRows = remember(request) {
        mutableStateListOf<ContactsWriter.DraftItem>().apply {
            val init = detail?.emails?.map { ContactsWriter.DraftItem(it.value, it.type) }
            if (!init.isNullOrEmpty()) addAll(init) else add(
                ContactsWriter.DraftItem("", Email.TYPE_HOME)
            )
        }
    }
    val postalRows = remember(request) {
        mutableStateListOf<ContactsWriter.DraftPostal>().apply {
            val init = detail?.addresses?.map {
                ContactsWriter.DraftPostal(it.street, it.city, it.region, it.postcode, it.type)
            }
            if (!init.isNullOrEmpty()) addAll(init) else add(
                ContactsWriter.DraftPostal("", "", "", "", StructuredPostal.TYPE_HOME)
            )
        }
    }

    val canSave = !saving && (name.isNotBlank() || phoneRows.any { it.value.isNotBlank() })

    fun save() {
        if (!canSave) return
        saving = true
        // 只有第一行网站，原机的多行网站用得极少，这里保持单值
        val websites = if (website.isBlank()) emptyList()
        else listOf(
            ContactsWriter.DraftItem(
                website,
                detail?.websites?.firstOrNull()?.type ?: Website.TYPE_HOMEPAGE
            )
        )
        val draft = ContactsWriter.Draft(
            name = name,
            nickname = nickname,
            company = company,
            department = department,
            jobTitle = jobTitle,
            phones = phoneRows.toList(),
            emails = emailRows.toList(),
            addresses = postalRows.toList(),
            websites = websites,
            birthday = birthday,
            note = note,
            starred = starred,
        )
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ContactsWriter.save(context, detail, draft)
            }
            saving = false
            when (result) {
                is ContactsWriter.SaveResult.Ok ->
                    onSaved(if (isNew) "已新建联系人" else "已保存修改")
                is ContactsWriter.SaveResult.Fail -> onFailed(result.message)
            }
        }
    }

    /*
     * 全屏覆盖层，不是 Dialog。
     *
     * Dialog 是独立 window，Compose 在里面读不到 insets（实测 status/nav/ime 全 0），
     * 内容会一路画到导航栏底下，只能从外面把高度传进来打补丁。
     * 放在 Activity 的 window 里就没这问题：windowInsetsPadding(safeDrawing)
     * 一次把状态栏、导航栏、键盘都处理掉（safeDrawing = systemBars + ime 的并集，
     * 键盘弹出时自动取较大值，不会出现导航栏 + 键盘双份留白）。
     */
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            // 吃掉下层列表的点击，避免覆盖层显示时误触到背后的行
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // ---------- 顶栏 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(start = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (!saving) onDismiss() }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = SonyColors.TextSecondary
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (isNew) "新建联系人" else "编辑联系人",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    color = SonyColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { save() }, enabled = canSave) {
                    Text(
                        text = if (saving) "保存中…" else "保存",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (canSave) SonyColors.Theme else SonyColors.TextTertiary
                    )
                }
            }
            HorizontalDivider(color = Color(0xFFE8E8E8), thickness = 0.5.dp)

            // ---------- 表单 ----------
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 14.dp, bottom = 28.dp)
                ) {
                // ---- 头像（默认占位，和卡片、原机一致） ----
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    // 编辑页不传 colorKey，用原机那种中性灰 —— 大号彩色头像在这里太跳
                    DefaultAvatar(size = 96.dp)
                }
                Spacer(Modifier.height(20.dp))

                // ---- 姓名 ----
                FieldRow(
                    value = name,
                    onValueChange = { name = it },
                    label = "姓名",
                    enabled = !saving
                )

                // ---- 电话 ----
                SectionHeader("电话")
                phoneRows.forEachIndexed { index, item ->
                    MultiRow(
                        typeLabel = PHONE_TYPES.label(item.type),
                        typeOptions = PHONE_TYPES,
                        onTypeChange = { phoneRows[index] = item.copy(type = it) },
                        value = item.value,
                        onValueChange = { phoneRows[index] = item.copy(value = it) },
                        label = "电话号码",
                        keyboardType = KeyboardType.Phone,
                        enabled = !saving,
                        onRemove = { phoneRows.removeAt(index) },
                        canRemove = phoneRows.size > 1 || item.value.isNotBlank()
                    )
                }
                AddRowButton("添加电话", enabled = !saving) {
                    phoneRows.add(
                        ContactsWriter.DraftItem("", Phone.TYPE_MOBILE)
                    )
                }

                // ---- 邮箱 ----
                SectionHeader("电子邮件")
                emailRows.forEachIndexed { index, item ->
                    MultiRow(
                        typeLabel = EMAIL_TYPES.label(item.type),
                        typeOptions = EMAIL_TYPES,
                        onTypeChange = { emailRows[index] = item.copy(type = it) },
                        value = item.value,
                        onValueChange = { emailRows[index] = item.copy(value = it) },
                        label = "电子邮件",
                        keyboardType = KeyboardType.Email,
                        enabled = !saving,
                        onRemove = { emailRows.removeAt(index) },
                        canRemove = emailRows.size > 1 || item.value.isNotBlank()
                    )
                }
                AddRowButton("添加电子邮件", enabled = !saving) {
                    emailRows.add(
                        ContactsWriter.DraftItem("", Email.TYPE_HOME)
                    )
                }

                // ---- 更多字段开关 ----
                MoreFieldsToggle(expanded = showMore, onToggle = { showMore = it })

                if (showMore) {
                // ---- 昵称 / 单位 ----
                SectionHeader("昵称与单位")
                FieldRow(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = "昵称",
                    enabled = !saving
                )
                Spacer(Modifier.height(10.dp))
                FieldRow(company, { company = it }, "公司", !saving)
                Spacer(Modifier.height(10.dp))
                FieldRow(department, { department = it }, "部门", !saving)
                Spacer(Modifier.height(10.dp))
                FieldRow(jobTitle, { jobTitle = it }, "职位", !saving)

                // ---- 地址 ----
                SectionHeader("地址")
                postalRows.forEachIndexed { index, item ->
                    PostalCard(
                        row = item,
                        enabled = !saving,
                        onChange = { postalRows[index] = it },
                        onRemove = { postalRows.removeAt(index) },
                        canRemove = postalRows.size > 1 || !item.isBlankRow()
                    )
                }
                AddRowButton("添加地址", enabled = !saving) {
                    postalRows.add(
                        ContactsWriter.DraftPostal("", "", "", "", StructuredPostal.TYPE_HOME)
                    )
                }

                // ---- 其他 ----
                SectionHeader("其他")
                FieldRow(
                    value = website,
                    onValueChange = { website = it },
                    label = "网站",
                    enabled = !saving,
                    keyboardType = KeyboardType.Uri,
                )
                Spacer(Modifier.height(10.dp))
                FieldRow(
                    value = birthday,
                    onValueChange = { birthday = it },
                    label = "生日（如 1990-05-12）",
                    enabled = !saving,
                )

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    enabled = !saving,
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp)
                )

                }   // if (showMore)

                // ---- 常用 ----
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("常用联系人", fontSize = 15.sp, color = SonyColors.TextPrimary)
                        Text(
                            "加到「常用联系人」页",
                            fontSize = 11.sp,
                            color = SonyColors.TextTertiary
                        )
                    }
                    Switch(
                        checked = starred,
                        onCheckedChange = { starred = it },
                        enabled = !saving,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = SonyColors.Theme
                        )
                    )
                }
                }
            }
        }
    }
}

private fun List<Pair<Int, String>>.label(type: Int): String =
    firstOrNull { it.first == type }?.second ?: "其他"

private fun ContactsWriter.DraftPostal.isBlankRow(): Boolean =
    street.isBlank() && city.isBlank() && region.isBlank() && postcode.isBlank()

// ------------------------------------------------------------------ 小组件

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = SonyColors.Theme,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun FieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp)
    )
}

/** 带类型下拉的一行（电话 / 邮箱） */
@Composable
private fun MultiRow(
    typeLabel: String,
    typeOptions: List<Pair<Int, String>>,
    onTypeChange: (Int) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    enabled: Boolean,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TypeChip(
            label = typeLabel,
            options = typeOptions,
            enabled = enabled,
            onSelect = onTypeChange
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp)
        )
        if (canRemove) {
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "删除这一行",
                    tint = SonyColors.TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Spacer(Modifier.width(18.dp))
        }
    }
}

/** 地址一块：类型 + 街道 / 城市 / 省 / 邮编 */
@Composable
private fun PostalCard(
    row: ContactsWriter.DraftPostal,
    enabled: Boolean,
    onChange: (ContactsWriter.DraftPostal) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF7F7F7))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeChip(
                label = POSTAL_TYPES.label(row.type),
                options = POSTAL_TYPES,
                enabled = enabled,
                onSelect = { onChange(row.copy(type = it)) }
            )
            Spacer(Modifier.weight(1f))
            if (canRemove) {
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "删除这个地址",
                        tint = SonyColors.TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        FieldRow(row.street, { onChange(row.copy(street = it)) }, "街道", enabled)
        Spacer(Modifier.height(8.dp))
        FieldRow(row.city, { onChange(row.copy(city = it)) }, "城市", enabled)
        Spacer(Modifier.height(8.dp))
        FieldRow(row.region, { onChange(row.copy(region = it)) }, "省/直辖市/自治区", enabled)
        Spacer(Modifier.height(8.dp))
        FieldRow(
            row.postcode, { onChange(row.copy(postcode = it)) }, "邮编", enabled,
            keyboardType = KeyboardType.Number
        )
    }
}

@Composable
private fun TypeChip(
    label: String,
    options: List<Pair<Int, String>>,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 13.sp, color = SonyColors.Theme)
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = SonyColors.Theme,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = Color.White
        ) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text, fontSize = 15.sp) },
                    onClick = {
                        onSelect(value)
                        open = false
                    }
                )
            }
        }
    }
}

/**
 * 「更多字段 / 收起更多字段」切换行，对应原机编辑页的「更多字段」。
 *
 * 收起时右侧顺带列出里面有什么（昵称 · 单位 · 地址 …），
 * 否则用户不知道点开能看到什么，折叠区就等于被藏起来了。
 */
@Composable
private fun MoreFieldsToggle(expanded: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onToggle(!expanded) }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (expanded) "−" else "+",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = SonyColors.Theme
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (expanded) "收起更多字段" else "更多字段",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = SonyColors.Theme
        )
        Spacer(Modifier.weight(1f))
        if (!expanded) {
            Text(
                text = "昵称 · 单位 · 地址 · 网站 · 生日 · 备注",
                fontSize = 11.sp,
                color = SonyColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AddRowButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text = "+ $label",
            fontSize = 14.sp,
            color = if (enabled) SonyColors.Theme else SonyColors.TextTertiary
        )
    }
}
