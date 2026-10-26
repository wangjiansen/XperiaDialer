# Xperia Dialer

自用安卓拨号器 —— 针对 Sony Xperia 1 V / Android 15。

当前阶段 **P0 + P1**：接管来电、自绘来电与通话界面、CallStyle 通知、锁屏全屏来电、音频路由、静音、DTMF。

后续规划（见文末）：P2 骚扰识别引擎、P3 T9 拼音、P4 通话记录改版。

---

## 一、环境要求

| 项 | 版本 |
|---|---|
| Android Studio | Ladybug (2024.2) 或更新 |
| JDK | 17+（AS 自带即可，本仓库 Gradle 用 17 编译） |
| compileSdk | 35（Android 15） |
| minSdk | 29（Android 10） |
| targetSdk | 35 |
| AGP / Kotlin / Compose | 8.7.3 / 2.0.21 / BOM 2024.12.01 |

真机：Sony Xperia 1 V，Android 15，开启「开发者选项 → USB 调试」。

---

## 二、构建与安装

> 本项目已在本机（Windows）完整编译通过：`BUILD SUCCESSFUL`，零错误零警告，
> APK 位于 `app/build/outputs/apk/debug/app-debug.apk`（约 24 MB）。
> 本机已备好 JDK 21 / Android SDK 35 / Gradle 8.9，见文末「本机环境」。

### 方式 A：Android Studio（推荐）

1. `File → Open` 选择 `XperiaDialer` 目录
2. 首次 Sync 时 Gradle JDK 选 17+（AS 自带即可）
3. `Run → Run 'app'`，直接装到 Xperia 1 V

### 方式 B：命令行

```bash
export JAVA_HOME="C:/Program Files/Java/oracleJdk-21"   # 17+ 均可
export ANDROID_HOME="C:/Users/wangj/.android-sdk"

cd XperiaDialer
./gradlew assembleDebug          # wrapper 已配置国内镜像，下载快
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

已在 `local.properties` 里写好了 `sdk.dir`，换机器时改这一行即可。

### 方式 C：USB 直连调试（日常开发推荐）

手机开「开发者选项 → USB 调试」并插上数据线，然后：

| 脚本 | 作用 |
|---|---|
| **`deploy.cmd`** | 双击即可：检查设备 → 编译 → 安装 → 启动 |
| **`logs.cmd`** | 只看本 App 的 logcat（自动按 pid 过滤），Ctrl+C 退出 |

命令行等价物（Git Bash）：

```bash
./deploy.sh      # 编译 + 安装 + 启动
./logs.sh        # 只跟本 App 的日志
```

> **PATH 坑**：Git Bash 里 Android SDK 路径必须用 `/c/Users/...` 形式，
> 写成 `C:/Users/...` 会导致 `adb: command not found`（`.cmd` 里用 Windows 形式即可）。

没有来电时可以用主页底部的「界面预览（调试）」直接渲染来电/通话界面，
不产生真实通话，用于检查布局与交互（详见 `CallManager.previewIncoming`）。

---

## 三、真机验收清单（P0 + P1）

按顺序验证，任何一步不过就先别往下走。
当前进度：**P0 链路已打通，P1 用界面预览走完了全流程**，真实来电待实测。

> 界面预览已实测通过（真机 XQ-DQ72 / Android 15）：
> 预览来电 → 接听 → 通话中计时 → DTMF 键盘 → 静音切换 → 挂断 → 自动退回主页，全链路正常。

### P0：链路打通

- [x] 安装后打开 App，主页显示状态卡片 + 资格自检面板 + 拨号盘
- [x] 点「默认电话应用 → 去设置」，**列表里能看到 Xperia Dialer** 并可选中
  - 看不到 → 检查 `MainActivity` 的 `ACTION_DIAL` intent-filter **是否都带 `<category android:name="android.intent.category.DEFAULT" />`**。
    ROLE_DIALER 的资格校验要求「`DIAL` 无 data」和「`DIAL` + `data scheme=tel`」两个组件都能被隐式解析，
    缺 DEFAULT 会导致系统判定不合格 —— 既不弹确认框，也不出现在列表里
- [x] 设为默认电话应用后，主页该卡片变绿
- [x] 资格自检面板四项全为「是」
- [ ] 他人拨打你的号码 → **Xperia Dialer 的来电界面弹出**（不是系统拨号器）
  - 自测办法：双卡机型用卡 2 拨卡 1；或让家人/同事打一通
- [ ] 查看 logcat：`adb logcat -s InCallServiceImpl CallManager CallScreening`
  - 应能看到 `onScreenCall number=...`、`onCallAdded state=2`（2 = RINGING）

### P1：功能完整

已在 **10086 实测通过**的项：

- [x] 主页拨号盘输入号码点「拨号」→ 正常拨出并进入通话中界面
- [x] 通话中「免提」→ 走扬声器，再点一次回听筒
- [x] 通话中「键盘」→ 展开 DTMF 键盘，按键音正常（客服语音菜单可用）
- [x] 通话中「挂断」→ 正常结束

待实测（需要真实来电，UI 层已用预览验证过）：

- [ ] 来电界面「接听」→ 进入通话中，时长开始计时
- [ ] 来电界面「拒接」→ 对方听到忙音，本机会话结束
- [ ] 通话中「静音」→ 对方听不到（切换后按钮变为「取消静音」）
- [ ] **锁屏状态**来电 → 来电界面能全屏弹出（Android 14+ 需要「锁屏全屏来电」权限，主页卡片可跳转授权）
- [ ] 来电时下拉通知栏 → 能看到 CallStyle 来电通知，带「接听 / 挂断」按钮
- [ ] 直接点通知上的「接听」→ 能接起来（不经 UI）
- [ ] 接蓝牙耳机 → 免提按钮外还能走蓝牙（本版未在运行时申请 BLUETOOTH_CONNECT，需手动在系统设置里给本 App 开蓝牙权限）

### 稳定性

- [ ] 连续打 3 通电话，无 ANR、无崩溃
- [ ] 通话中按返回键 / 锁屏再解锁，界面能恢复
- [ ] 杀掉 App 进程后再来电，仍能正常接管

---

## 四、代码结构

```
app/src/main/java/com/wangj/xperiadialer/
├── DialerApp.kt                 Application，创建通知渠道
├── MainActivity.kt              主页：角色/权限状态看板 + 拨号盘
├── RoleHelper.kt                默认拨号器 / 来电筛选 / 全屏通知 状态查询
├── ui/Theme.kt                  Material3 主题
├── call/
│   ├── InCallServiceImpl.kt     ★ Telecom 入口，onCallAdded/onCallRemoved
│   ├── CallManager.kt           ★ 通话状态中心（单例 StateFlow + 全部控制方法）
│   ├── InCallActivity.kt        来电/通话 Activity，锁屏标志位
│   ├── InCallScreen.kt          Compose UI：来电页 / 通话中页 / DTMF 键盘
│   ├── CallForegroundService.kt Android 14+ phoneCall 前台服务
│   ├── CallNotificationManager.kt  CallStyle 来电/通话中通知
│   └── CallActionReceiver.kt    通知上的接听/挂断按钮
└── screen/
    └── CallScreeningServiceImpl.kt  ★ 来电筛选（P2 骚扰识别接这里）
```

数据流：

```
Telecom ──> InCallServiceImpl ──> CallManager(StateFlow) ──> InCallScreen(Compose)
                   │                      ▲
                   └─> CallForegroundService ──> CallNotificationManager
```

---

## 五、几个关键实现说明

### 1. 为什么不需要悬浮窗权限

因为本 App 是**默认拨号器**并实现了 `InCallService`，来电界面由我们自己绘制，
骚扰标签可以直接渲染在来电页上。只有「不替换拨号器」的方案（如 SpamBlocker）
才需要 `SYSTEM_ALERT_WINDOW` 悬浮窗去盖一层卡片。

### 2. 必须存在的 Manifest 声明（缺一个就进不了默认电话应用列表）

```xml
<!-- MainActivity：两个 ACTION_DIAL filter 缺一不可 -->
<intent-filter><action android:name="android.intent.action.DIAL"/><data android:scheme="tel"/></intent-filter>
<intent-filter><action android:name="android.intent.action.DIAL"/></intent-filter>

<!-- InCallService：permission 拼写是 BIND_INCALL_SERVICE（不是 BIND_IN_CALL_SERVICE） -->
<service android:permission="android.permission.BIND_INCALL_SERVICE">
    <meta-data android:name="android.telecom.IN_CALL_SERVICE_UI"      android:value="true"/>
    <meta-data android:name="android.telecom.IN_CALL_SERVICE_RINGING" android:value="true"/>
</service>
```

### 3. CallScreeningService 的 5 秒硬约束

`onScreenCall()` 必须在 5 秒内调用 `respondToCall()`，否则系统解绑服务、丢弃响应，
并且**手机在这之前不会响铃**。所以 P2 阶段在线查询一律放到响应之后的异步流程。

### 4. Android 14+ 前台服务类型

已在 Manifest 声明 `android:foregroundServiceType="phoneCall"` +
`FOREGROUND_SERVICE_PHONE_CALL`。运行时还需满足「声明 `MANAGE_OWN_CALLS`」或
「持有 `ROLE_DIALER`」之一，否则 `startForeground` 抛 SecurityException。

### 5. Android 15 注意事项

- 不允许从 `BOOT_COMPLETED` 启动 phoneCall 前台服务（P3 的拼音索引预热要用 JobScheduler）
- 动态注册广播必须显式指定 `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED`
- Android 16 已知问题：被静音拦截的电话，系统仍会弹「未接来电」通知（issuetracker #474398435）

### 6. 黄页号码数据（可选，需要自己生成）

来电时显示「顺丰速运」而不是「未知号码」，靠的是 `assets/phonebook.tsv`：
600 余条企业/机构号码，由 [metowolf/vCards](https://github.com/metowolf/vCards) 转换而来。

```bash
# 一键下载最新数据并生成（需要 GitHub CLI 和 Python）
tools\update-phonebook.cmd

# 或者指定已有的数据目录
python tools/gen_phonebook.py --src <vcards 仓库>/data
```

**这个数据文件不在仓库里**（`.gitignore` 排除），原因是上游仓库没有 LICENSE，
打包分发有版权风险。数据缺失时 `PhoneBook.ensureLoaded()` 返回 false，
自动降级到内置的 `KnownNumbers`（71 条银行/快递/航空/运营商），功能不受影响。

号码匹配做了三层归一化：
- 去 `+86` / `0086` 前缀（含漫游时的 `+8695338` 这种短号）
- 带区号固话同时存 `01053153053` 和去 0 的 `1053153053`
- 不做「纯本地 8 位号」匹配 —— 不同城市会撞号，误判代价高于收益

---

## 六、后续阶段预留的扩展点

| 阶段 | 接入位置 |
|---|---|
| **P2 骚扰识别** | `CallScreeningServiceImpl.onScreenCall()` 做同步判定（本地库，<50ms）；<br>`CallInfo` 增加 `spamScore / spamLabel / geoLocation` 字段；<br>`IncomingContent` / 通话记录行渲染标签；<br>在线 API 查询放到 `respondToCall` 之后的协程 |
| **P3 T9 拼音** | 新建 `data/ContactIndexRepository`（Room 存 `quanpin / shouzimu / t9Quanpin / t9Shouzi`）；<br>用 TinyPinyin `com.github.promeg:tinypinyin:2.0.3` 预计算；<br>拨号盘结果排序优先级：全拼 T9 前缀 > 首字母 T9 前缀 > 号码子串 |
| **P4 通话记录** | 新建 `ui/history/`，读 `CallLog.Calls` + 左连接自建标记表；<br>同号码聚合折叠、时间分组、类型筛选 |

---

## 七、本机环境（已配置好，直接复用）

构建链已在本机装好，不需要重新下载：

| 组件 | 路径 |
|---|---|
| JDK 21 | `C:/Program Files/Java/oracleJdk-21` |
| Android SDK | `C:/Users/wangj/.android-sdk`（platforms/android-35、build-tools/35.0.0、platform-tools） |
| Gradle 8.9 | `C:/Users/wangj/.gradle-dist/gradle-8.9` 及项目内 `gradlew` |

**网络经验**：直连 `dl.google.com` / `services.gradle.org` 曾慢到 16 KB/s，
腾讯镜像约 10 MB/s。`gradle-wrapper.properties` 已指向腾讯镜像；
Maven 依赖若慢，可临时用 `--init-script <path>/mirrors.gradle` 走腾讯 Maven 镜像。

---

## 八、已知限制

- 未实现多通话（会议/保持切换）的完整交互，多通来电时只显示第一路
- 未做通话录音（Android 15 上需要 Shizuku 或系统支持，且涉及合规）
- 蓝牙音频需在系统设置里手动给本 App 授予「附近设备」权限
- 未处理 VoLTE/IMS 与 Xperia 固件的特殊兼容，需真机反复验证
