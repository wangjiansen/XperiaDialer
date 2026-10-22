# Sony Dialer UI 规范（提取自系统 APK）

为了让自研拨号器和系统原生视觉一致，直接从真机上的 Sony Dialer 反解出设计参数。
本文档是 P2–P4 阶段做界面时的**唯一配色与尺寸依据**，不要凭感觉另起一套。

## 一、数据来源与复现方法

```bash
# 1. 定位并提取系统拨号器
adb shell pm path com.sonymobile.android.dialer
#   → package:/product/priv-app/Dialer/Dialer.apk
MSYS_NO_PATHCONV=1 adb pull /product/priv-app/Dialer/Dialer.apk ./Dialer.apk

# 2. 导出资源表（约 11 万行）
aapt2 dump resources Dialer.apk > res.txt

# 3. 查色值 / 尺寸
grep -A2 "color/dialer_theme_color" res.txt
grep -A2 "dimen/dialpad_key_height" res.txt
```

真机：**XQ-DQ72（Xperia 1 V 国行，Android 15）**，包名 `com.sonymobile.android.dialer`，
路径 `/product/priv-app/Dialer/Dialer.apk`（16 MB）。
> 注意：这台机器的系统拨号器是 **Sony 自家**的（基于 AOSP Dialer 定制），**不是 Google Phone**。

## 二、色板

| 资源名 | 色值 | 用途 | 本项目常量 |
|---|---|---|---|
| `dialer_theme_color` | `#2A56C6` | 主题蓝：拨号数字、选中态、链接 | `SonyColors.Theme` |
| `dialer_theme_color_dark` | `#1C3AA9` | 主题蓝深色 | `SonyColors.ThemeDark` |
| `dialer_call_green` | `#00C853` | 接听键 | `SonyColors.CallGreen` |
| `dialer_end_call_button_color` | `#BD2A2A` | 挂断键 | `SonyColors.EndCallRed` |
| `dialer_red` | `#C53929` | 未接来电、错误 | `SonyColors.Red` |
| `dialer_background_color` | `#FAFAFA` | 页面底色（**不是纯白**） | `SonyColors.Background` |
| `dialer_secondary_color` | `#F50057` | 强调品红：悬浮拨号键 | `SonyColors.Accent` |
| `dialer_divider_line_color` | `#D8D8D8` | 分割线 | `SonyColors.Divider` |
| `incall_dialpad_background` | `#FFFFFF` | 通话中拨号盘底色 | `SonyColors.DialpadBg` |
| `letter_tile_font_color` | `#FFFFFF` | 字母头像文字 | — |
| `letter_tile_default_color` | `#CCCCCC` | 无数据时的头像底色 | — |

## 三、字母头像调色板

`array/letter_tile_colors`，共 14 色，标准 Material 500：

```
#DB4437  #E91E63  #9C27B0  #673AB7  #3F51B5  #4285F4  #039BE5
#0097A7  #009688  #0F9D58  #689F38  #EF6C00  #FF5722  #757575
```

取色规则：**按号码 hash 稳定取模**，保证同一号码每次颜色一致（`SonyColors.avatarColor()`）。

深色模式对应 `array/letter_tile_colors_dark`（14 色，暗态变体）。

## 四、尺寸与字号

| 项 | 值 | 说明 |
|---|---|---|
| `dialpad_key_height` | **64dp** | 单个拨号键高度 |
| `dialpad_key_numbers_default_size` | **36dp** | 数字字号（We 用 34sp，视觉近似） |
| `dialpad_space_above_keys` | 14dp | 键区上间距 |
| `dialpad_space_below_keys` | 8dp | 键区下间距 |
| `dialpad_key_number_default_margin_bottom` | 3dp | 数字与下方字母间距 |
| `contact_photo_size` | **48dp** | 列表联系人头像 |
| `call_log_action_height` | 48dp | 列表右侧回拨按钮 |
| `call_log_action_icon_dimen` | 24dp | 按钮内图标 |
| `call_log_primary_text_size` | **16sp** | 号码/姓名主文字 |
| `call_log_detail_text_size` | **12sp** | 第二行：SIM + 归属地 + 类型 |
| `call_log_day_group_heading_size` | 14sp | 日期分组标题 |
| `call_log_day_group_padding_top/bottom` | 15dp / 9dp | 分组标题上下内边距 |
| `call_log_horizontal_margin` | 8dp | 列表外边距 |
| `call_log_list_item_info_margin_start` | 16dp | 文字区左侧缩进 |
| `call_log_name_margin_bottom` | 2dp | 主文字与详情间距 |

## 五、图标提取

`aapt2 dump resources` 的输出里**带真实文件路径**，可以直接从 APK unzip 出原图：

```bash
grep -A4 "drawable/quantum_ic_call_white_24$" res.txt
#   (xxhdpi) (file) res/drawable-xxhdpi-v4/quantum_ic_call_white_24.png type=PNG

for d in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  unzip -o -j Dialer.apk "res/drawable-${d}-v4/quantum_ic_call_white_24.png" -d "icons/$d"
done
```

已提取并落入本项目 `app/src/main/res/drawable-{m,h,xh,xxh,xxxh}dpi/ic_sony_call_white.png`，
用于拨号按钮（绿色圆 `#00C853` + 白色听筒）。

Sony 拨号盘上那个按钮的规格：**56dp 圆形 / 24dp 图标 / 绿色 `#00C853`**，
浮在键盘右下角（AOSP Dialer 的 dialpad FAB）。本项目放在键盘下方居中 56dp。

其他可复用图标（同名取各密度即可）：

| 资源名 | 用途 |
|---|---|
| `quantum_ic_call_white_24` | 呼叫（拨号按钮） |
| `quantum_ic_call_end_white_24` | 挂断 |
| `quantum_ic_dialpad_white_24` | 拨号盘入口 |
| `quantum_ic_call_missed_vd_theme_24` | 未接来电 |
| `quantum_ic_call_made_vd_theme_24` | 去电 |
| `quantum_ic_call_received_vd_theme_24` | 来电 |

## 六、各界面设计要点

### 拨号盘
- 按键**无背景容器**，数字直接浮在白底上，只有按压涟漪
- 数字用主题蓝 `#2A56C6`，下方 10sp 灰色字母（ABC / DEF / GHI / JKL / MNO / PQRS / TUV / WXYZ）
- `1` 下方是语音信箱图标，`0` 下方是 `+`
- 拨号键是**绿色圆形**（`#00C853`），浮在键盘下方，不是完整一行
- 退格键在键盘右上外侧

### 通话记录（P4 参考）
- 顶部：圆角搜索框（"搜索联系人"）+ 麦克风 + 三点菜单
- 每行：48dp 彩色字母头像 → 主文字（姓名或号码，16sp）→ 第二行 12sp 详情
- 第二行结构：`[SIM 标记] [类型：手机/中国/黑龙江省哈尔滨市] [日期]`
  - **Sony 自带归属地库**，直接显示「黑龙江省哈尔滨市」
- 未接来电：红色 `#C53929` 的 ✖ 图标，整行不加底色
- 来电/去电用小箭头图标（↙ 来电 / ↗ 去电）
- 右侧 48dp 回拨按钮
- 底部 3 个 tab：常用联系人（星）/ 通话记录（时钟）/ 通讯录（人）
- 右下角品红 `#F50057` 圆形 FAB，图标是拨号盘

### 来电 / 通话中（本项目当前实现）
- 背景 `#FAFAFA`
- 头像：来电 120dp、通话中 96dp，字母头像 + 调色板取色
- 号码 30sp（来电）/ 28sp（通话中），姓名 16sp/15sp 灰色
- 状态文字 14sp 灰色（来电 / 通话中 / 正在拨号…）
- 来电两个按钮 76dp 圆：拒接 `#BD2A2A`、接听 `#00C853`
- 通话中功能键 68dp：未激活 `#F0F0F0` 底 + `#424242` 字，激活态换主题蓝
- 挂断 76dp 红，单独一行居中

## 七、落地情况

| 项 | 状态 |
|---|---|
| `SonyColors` / `LetterAvatar` / `SonyDialPad` | ✅ `ui/SonyDesign.kt` |
| Material3 主题改为 Sony 配色 | ✅ `ui/Theme.kt` |
| 来电 / 通话中界面重做 | ✅ `call/InCallScreen.kt` |
| 主页拨号盘重做 | ✅ `MainActivity` 复用 `SonyDialPad` |
| 拨号按钮换成原机听筒图标 | ✅ `ic_sony_call_white.png`（5 档密度）+ 56dp 绿圆 |
| 拨号键行列对齐（lineHeight 坑） | ✅ 见下文"已知差异" |
| 通话记录界面（P4） | ⬜ 待做，按第六节规格 |
| 联系头像读真实联系人照片 | ⬜ 待做（当前统一用字母头像） |
| 挂断 / 未接 / 来电去电图标 | ⬜ 已定位资源名，P2 一并接入 |

## 八、已知差异

- 数字字号：Sony 36dp，本项目 34sp（按视觉微调，因为 sp 与 dp 在字体缩放下的表现不同）
- Sony 拨号盘整块更贴近底部、间距更紧凑；本项目受 `verticalScroll` 布局限制略有差异
- 未实现「语音信箱图标」（`1` 下方）与退格键位置微调
