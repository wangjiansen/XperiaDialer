package com.wangj.xperiadialer

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangj.xperiadialer.call.CallManager
import com.wangj.xperiadialer.call.CallNotificationManager
import com.wangj.xperiadialer.call.InCallActivity
import com.wangj.xperiadialer.data.CallLogRepository
import com.wangj.xperiadialer.data.ContactsRepository
import com.wangj.xperiadialer.data.PhoneLocationDb
import com.wangj.xperiadialer.data.SpamAnalyzer
import com.wangj.xperiadialer.data.SpamLevel
import com.wangj.xperiadialer.data.SpamVerdict
import com.wangj.xperiadialer.ui.HighRefresh
import com.wangj.xperiadialer.ui.HomeScaffold
import com.wangj.xperiadialer.ui.PermissionSheet
import com.wangj.xperiadialer.ui.SonyColors
import com.wangj.xperiadialer.ui.SonyDialPad
import com.wangj.xperiadialer.ui.holdToRepeat
import com.wangj.xperiadialer.ui.XperiaDialerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val startNumber = mutableStateOf("")

    /**
     * 从桌面图标再次进入时递增。
     *
     * Activity 是 singleTop，从桌面点图标回来不会重建，Compose 里 `tab` 的 remember
     * 也就保留着上次停留的页面。用户期望的是「每次打开都从通话记录开始」，
     * 所以用这个 tick 通知 HomeScaffold 把 tab 拉回去。
     */
    private val homeResetTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 列表滚动、拨号盘动画都靠它跑满高刷
        HighRefresh.apply(this)
        startNumber.value = intent?.data?.schemeSpecificPart ?: ""
        setContent {
            XperiaDialerTheme {
                MainScreen(
                    initialNumber = startNumber.value,
                    homeResetTick = homeResetTick.intValue,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startNumber.value = intent.data?.schemeSpecificPart ?: ""
        homeResetTick.intValue++
    }
}


private val RUNTIME_PERMISSIONS = buildList {
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_CONTACTS)
    // 编辑/新建/删除/收藏联系人都要写权限，读权限单独一个
    add(Manifest.permission.WRITE_CONTACTS)
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.ANSWER_PHONE_CALLS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

@Composable
fun MainScreen(initialNumber: String, homeResetTick: Int = 0) {
    val context = LocalContext.current
    var actionTick by remember { mutableIntStateOf(0) }
    var resumeTick by remember { mutableIntStateOf(0) }
    var number by remember { mutableStateOf(initialNumber) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    // 从系统设置页返回时刷新状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(initialNumber) { number = initialNumber }
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            toastMsg = null
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { actionTick++ }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        actionTick++
        if (result.resultCode != Activity.RESULT_OK) {
            // 系统拒绝了角色申请（多半是资格校验没过），改走系统设置页让用户手动选
            val fb = RoleHelper.buildSettingsFallback(context)
            if (RoleHelper.safeStart(context, fb)) {
                toastMsg = "系统未弹出确认框，已打开系统设置页，请在「电话/拨号应用」里手动选择本应用"
            } else {
                toastMsg = "角色申请被拒绝（resultCode=${result.resultCode}），请到系统设置手动修改"
            }
        }
    }

    val refresh = actionTick + resumeTick

    val isDialer = remember(refresh) { RoleHelper.isDefaultDialer(context) }
    val isScreening = remember(refresh) { RoleHelper.isCallScreeningApp(context) }
    val hasNotify = remember(refresh) { CallNotificationManager.hasNotificationPermission(context) }
    val canFsi = remember(refresh) { RoleHelper.canUseFullScreenIntent(context) }
    val permsOk = remember(refresh) { RUNTIME_PERMISSIONS.all { granted(context, it) } }

    /** 角色申请：RoleManager 弹窗优先，失败自动降级到系统设置页 */
    val requestRole: (String, String) -> Unit = { role, label ->
        val intent = RoleHelper.buildRequestRoleIntent(context, role)
        var launched = false
        if (intent != null) {
            launched = runCatching { roleLauncher.launch(intent) }.isSuccess
        }
        if (!launched) {
            val fb = RoleHelper.buildSettingsFallback(context)
            if (RoleHelper.safeStart(context, fb)) {
                toastMsg = "本设备未开放「$label」申请入口，已打开系统设置页，请手动选择本应用"
            } else {
                toastMsg = "未找到「$label」设置入口，请到系统设置手动修改"
            }
        }
    }

    var showPermissions by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf<SpamVerdict?>(null) }
    // 预览来电要先异步查通讯录取名字，所以需要一个组合作用域
    val previewScope = rememberCoroutineScope()

    // 输入 5 位以上实时判定，结果直接显示在号码下方
    LaunchedEffect(number) {
        verdict = if (number.length >= 5) {
            withContext(Dispatchers.IO) { SpamAnalyzer.analyze(context, number) }
        } else {
            null
        }
    }

    // ---------- 通话记录 / 通讯录 ----------
    var callLogs by remember { mutableStateOf<List<CallLogRepository.Entry>>(emptyList()) }
    var contacts by remember { mutableStateOf<List<ContactsRepository.Entry>>(emptyList()) }
    var callLogLoading by remember { mutableStateOf(true) }
    var contactsLoading by remember { mutableStateOf(true) }

    val hasCallLog = remember(refresh) { granted(context, Manifest.permission.READ_CALL_LOG) }
    val hasContacts = remember(refresh) { granted(context, Manifest.permission.READ_CONTACTS) }
    val hasWriteContacts = remember(refresh) { granted(context, Manifest.permission.WRITE_CONTACTS) }

    /*
     * 通话记录里的名字用通讯录的**当前**名字覆盖。
     *
     * CallLog.CACHED_NAME 是系统在通话发生时缓存的，改了联系人的名字它不会立即更新，
     * 表现就是「刚在卡片里改完名字，通话记录里还是旧的」。
     * 通讯录本来就加载好了，这里按号码建索引做一次覆盖，500 条也是微秒级。
     */
    val contactsByDigits = remember(contacts) {
        contacts.associateBy { it.digits }
    }
    val displayCallLogs = remember(callLogs, contactsByDigits) {
        if (contactsByDigits.isEmpty()) {
            callLogs
        } else {
            callLogs.map { e ->
                val hit = PhoneLocationDb.normalize(e.number)?.let { contactsByDigits[it] }
                if (hit != null && hit.name.isNotBlank()) e.copy(name = hit.name) else e
            }
        }
    }

    LaunchedEffect(refresh, hasCallLog, hasContacts) {
        callLogLoading = true
        contactsLoading = true
        callLogs = if (hasCallLog) {
            withContext(Dispatchers.IO) { CallLogRepository.query(context) }
        } else {
            emptyList()
        }
        callLogLoading = false
        contacts = if (hasContacts) {
            withContext(Dispatchers.IO) { ContactsRepository.query(context) }
        } else {
            emptyList()
        }
        contactsLoading = false
    }

    HomeScaffold(
        number = number,
        verdict = verdict,
        onDigit = { number += it },
        onBackspace = { number = number.dropLast(1) },
        onDial = { num -> dial(context, num, permLauncher) },
        // 点 T9 搜索结果：先把号码落进输入框再拨，万一拨出失败界面上留的是号码本身
        // 而不是刚才那串搜索用的数字
        onPickContact = { contact ->
            number = contact.number
            dial(context, contact.number, permLauncher)
        },
        // 删除通话记录（来自「通话详情」），删完重新拉一次列表
        onDeleteCallLog = { id ->
            previewScope.launch {
                withContext(Dispatchers.IO) { CallLogRepository.delete(context, id) }
                actionTick++ // refresh 是 actionTick + resumeTick 的只读组合，递增它才能触发重载
            }
        },
        callLogs = displayCallLogs,
        callLogLoading = callLogLoading,
        hasCallLogPermission = hasCallLog,
        onRequestCallLogPermission = {
            permLauncher.launch(arrayOf(Manifest.permission.READ_CALL_LOG))
        },
        contacts = contacts,
        contactsLoading = contactsLoading,
        hasContactsPermission = hasContacts,
        onRequestContactsPermission = {
            permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
        },
        hasWriteContactsPermission = hasWriteContacts,
        onRequestWriteContactsPermission = {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS
                )
            )
        },
        // 联系人被编辑/删除/收藏后重载通话记录与通讯录两份数据
        onContactsChanged = { actionTick++ },
        onToast = { toastMsg = it },
        onOpenPermissions = { showPermissions = true },
        onPreviewIncoming = {
            /*
             * 名字留空、号码取通讯录里真实存在的一个。
             *
             * 这样预览才和真实来电一致：Telecom 不提供 callerDisplayName，
             * 名字得靠通讯录解析出来。传「测试来电」是假数据，会把
             * 「来电不显示联系人」这类问题掩盖掉。
             */
            previewScope.launch {
                val name = withContext(Dispatchers.IO) {
                    ContactsRepository.lookupName(context, PREVIEW_INCOMING_NUMBER).orEmpty()
                }
                CallManager.previewIncoming(PREVIEW_INCOMING_NUMBER, name)
                openInCall(context)
            }
        },
        onPreviewActive = {
            CallManager.previewActive("10086", "中国移动")
            openInCall(context)
        },
        onOpenDiagnostics = { showDiagnostics = true },
        homeResetTick = homeResetTick,
    )

    if (showPermissions) {
        PermissionSheet(
            isDialer = isDialer,
            isScreening = isScreening,
            hasNotify = hasNotify,
            canFsi = canFsi,
            permsOk = permsOk,
            onDismiss = { showPermissions = false },
            onRequestDialer = { requestRole(RoleManager.ROLE_DIALER, "默认电话应用") },
            onRequestScreening = { requestRole(RoleManager.ROLE_CALL_SCREENING, "来电筛选") },
            onRequestNotify = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            },
            onRequestFsi = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    if (!RoleHelper.safeStart(
                            context,
                            RoleHelper.buildFullScreenIntentSettings(context)
                        )
                    ) {
                        toastMsg = "未找到全屏通知设置入口"
                    }
                }
            },
            onRequestPerms = { permLauncher.launch(RUNTIME_PERMISSIONS) },
        )
    }

    if (showDiagnostics) {
        DiagnosticsSheet(refresh = refresh, onDismiss = { showDiagnostics = false })
    }
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** 打开通话界面（预览模式用） */
private fun openInCall(context: Context) {
    val intent = Intent(context, InCallActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * 预览来电用的号码。改成你自己通讯录里真实存在的一个，
 * 才能验证「姓名由通讯录解析」这条链路；默认值是虚构号码。
 */
private const val PREVIEW_INCOMING_NUMBER = "13800138000"

/** 识别结果展示区的固定高度 */
private val SPAM_PREVIEW_HEIGHT = 28.dp

private fun dial(
    context: Context,
    number: String,
    permLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
) {
    if (number.isBlank()) return
    if (!granted(context, Manifest.permission.CALL_PHONE)) {
        permLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
        return
    }
    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * 角色资格自检面板。
 * 「InCallService 可解析」「ACTION_DIAL 可解析」两项都为 true，
 * 系统才会弹出默认电话应用的确认框。
 */
@Composable
private fun DiagnosticsCard(context: Context, refresh: Int) {
    val currentDialer = remember(refresh) { RoleHelper.currentDialerPackage(context) ?: "(无)" }
    val roleAvailable = remember(refresh) {
        RoleHelper.isRoleAvailable(context, RoleManager.ROLE_DIALER)
    }
    val inCallOk = remember(refresh) { RoleHelper.inCallServiceResolvable(context) }
    val dialOk = remember(refresh) { RoleHelper.dialActivityResolvable(context) }
    val dialTelOk = remember(refresh) { RoleHelper.dialTelResolvable(context) }
    val dialPkgs = remember(refresh) { RoleHelper.visibleDialerPackages(context) }
    val inCallPkgs = remember(refresh) { RoleHelper.visibleInCallServicePackages(context) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("资格自检", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            DiagRow("当前默认拨号器", currentDialer, null)
            DiagRow("ROLE_DIALER 可用", null, roleAvailable)
            DiagRow("InCallService 可解析", null, inCallOk)
            DiagRow("DIAL 可解析", null, dialOk)
            DiagRow("DIAL+tel 可解析", null, dialTelOk)
            DiagList("系统可见拨号应用", dialPkgs, context.packageName)
            DiagList("系统可见 InCallService", inCallPkgs, context.packageName)
            if (!inCallOk || !dialOk || !dialTelOk) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "四项必须全为「是」，系统才会认可 ROLE_DIALER 资格；任一为否都会导致不弹框、列表里也不出现",
                    fontSize = 12.sp,
                    color = Color(0xFFC62828)
                )
            }
        }
    }
}

@Composable
private fun DiagList(label: String, pkgs: List<String>, selfPkg: String) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(
            "$label (${pkgs.size})",
            fontSize = 13.sp,
            fontWeight = if (pkgs.contains(selfPkg)) FontWeight.Normal else FontWeight.Medium
        )
        if (pkgs.isEmpty()) {
            Text("(空)", fontSize = 11.sp, color = Color(0xFFC62828))
        } else {
            pkgs.forEach { p ->
                Text(
                    "· $p" + if (p == selfPkg) "  ←本应用" else "",
                    fontSize = 11.sp,
                    color = if (p == selfPkg) Color(0xFF2E7D32) else Color(0xFF616161)
                )
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String?, ok: Boolean?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (ok != null) {
            Text(
                if (ok) "是" else "否",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
        } else {
            Text(
                value ?: "-",
                fontSize = 12.sp,
                color = Color(0xFF616161)
            )
        }
    }
}

/** 调试诊断：系统侧组件注册与角色生效状态，只在三点菜单里打开 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsSheet(refresh: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
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
                text = "调试诊断",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = SonyColors.TextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "系统侧组件注册与角色生效状态",
                fontSize = 12.sp,
                color = SonyColors.TextSecondary
            )
            Spacer(Modifier.height(12.dp))
            DiagnosticsCard(context = context, refresh = refresh)
        }
    }
}
