# 口袋键鼠（PocketKeyboard）

把安卓手机变成**蓝牙键盘 + 蓝牙触控板**外设的极简 App：黑白主题、大面积极简、无缝键帽、苹果式设计语言、OLED 省电纯黑背景。

配对 iPad / iPhone / Mac / Windows 等设备后，手机即可作为它们的蓝牙键盘与触控板使用，支持同时与多台设备配对并按需切换控制目标。

**English version: [README.en.md](README.en.md)**

## 演示截图

| 配对页 | 键盘页（竖屏融合布局） |
| --- | --- |
| ![配对页](docs/screenshots/01_pairing.png) | ![键盘页](docs/screenshots/02_keyboard.png) |

| 融合页 + 系统输入法 | 数字小键盘 |
| --- | --- |
| ![系统输入法上屏](docs/screenshots/03_trackpad.png) | ![数字小键盘](docs/screenshots/04_numpad.png) |

## 功能特性

- **蓝牙 HID 外设**：基于系统 `BluetoothHidDevice` profile 注册键盘 + 触控板复合外设（含标准 HID 报告描述符），配对后被控设备将其识别为蓝牙键盘/触控板
- **广播名与手机区分**：App 启动后把本机蓝牙名设为 `PocketKeyboard-<品牌>`（如 `PocketKeyboard-redmi`），被控设备蓝牙菜单里一眼认出这是口袋键鼠而不是手机本体
- **配对页**：显示固定配对码 `0000`；配对进行中实时显示**当次真实 SSP 数字**（与被控设备上显示的完全一致，见「配对码说明」）；支持同时与多台设备配对；首次连接某设备时选择「苹果设备 / 其他设备」以适配布局与手势（选择持久化，不重复询问）
- **添加被控设备**：App 内扫描附近蓝牙设备并直接发起配对（`createBond`），添加、连接、断开、删除被控设备全程不离开 App；行内「连接 / 断开」按钮管理连接（断开是粘性的，对端回连也不会自动恢复）；左滑删除解除配对
- **连接可靠性**（断联全面自愈）：连接带 9 秒看门狗（回调丢失 / 对端不接受自动判失败并退避重试，「连接中」绝不永久挂住）+ 每 3 秒按系统真值对账；重复派发的断开回调自动去重（MIUI 实测会把同一次回调派发两遍）；重连**永不放弃**（退避 15s 封顶后一直守护重连）；发送失败即自愈——「系统说已连接却发不出报告」自动重注册 HID（锁屏 / 后台被系统收走注册的确定性恢复），死链自动断开重连并补发刚才的输入；回前台自动探活（两份零位移鼠标报告）；对端陈旧会话自动摁掉重连；注册楔死（部分 ROM 会在进程被杀后卡死 HID 注册）自动自愈，root 设备可重启蓝牙栈恢复
- **CONTROL 按钮**：未连接任何设备时灰色不可用，连接成功后变黑可点击进入控制界面
- **五指挥势**（全局，任意页面生效，带动效与文字提示）：
  - 五指短按（5 指同时按下、快速原地抬起）→ 返回键盘页面
  - 刻意只保留这一个简单手势：收缩 / 张开 / 横滑那类复杂几何判定在真机上太难触发、互相误判，已整体移除
- **竖屏融合布局**（键盘页 / 触控板页共用）：上半触控板（右上角「123」小键盘开关、底部短竖线分割的左右点击区、当前设备名）+ 下半 26 键手机输入法风格 QWERTY（三行字母 10/9/7 + shift / 退格 / 空格 / 回车，shift 轻点粘滞一次、按住组合大写）；键帽一律大写显示
- **87 键键盘页**（横屏全屏）：TKL 无小键盘区布局，苹果 / Windows 双套键位；黑底白字、键间无缝；按压有下陷视觉效果 + 触觉反馈
- **横屏全屏**：横屏下键盘模式铺满 87 键 TKL、触控板模式铺满触控板（manifest 声明 configChanges，旋转不重建 Activity）
- **dock 自动隐藏**：底部模式切换栏在键盘 / 触控板页默认隐藏、把空间腾给键盘与触控板；单指从屏幕左缘或右缘向内滑过 24dp 即以苹果式 spring 唤出，切换页面模式后再次自动隐藏
- **fn 组合功能**（fn 粘滞，顶部有提示）：
  - `fn + 空格`：循环调节屏幕背光亮度（iOS 风格亮度 HUD）
  - `fn + C`：循环切换键帽字体颜色（白 / 橙 / 红）
  - `fn + S`：循环切换键帽字号（小 / 中 / 大）
  - `fn + V`：循环切换震感强度（关 / 弱 / 中 / 强），与触控板反馈强度同步
- **F 行媒体键**：默认行为为音量加减、静音、亮度加减、上一首/下一首、播放暂停等常用快捷键；与 `fn` 同按才是本义 F1–F12
- **触控板页**：适配两套手势集——
  - Windows 模式：底部左右按键模拟区（左键/右键，可拖拽）、双指滚动、双指缩放（映射 Ctrl+滚轮）、三指上滑/下滑/左右滑、四指点击 = Win 键
  - Apple 模式：单指点击 = 左键、双指点击 = 右键、双指滚动、双指捏合/旋转（映射 Ctrl+滚轮）、三指上滑/左右滑
- **数字小键盘**：触控板右上角「123」极简开关，底部 sheet 滑入 4×6 数字键盘（含 0/00、退格、回车、运算符、清空），服务大量数字输入场景
- **OLED 省电**：整体纯黑背景，白色文字与图标

## 环境要求与构建

- JDK 17、Android SDK（Platform 35、Build-Tools 35.x）
- minSdk 28（Android 9）· targetSdk 35 · Kotlin 2.0.21 · AGP 8.7.2 · Gradle 8.11.1（wrapper 已内置）

```bash
# 克隆后直接用 wrapper 构建（无需预装 Gradle）
git clone https://github.com/haohaoo3o/PocketKeyboard.git
cd PocketKeyboard
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或直接用 Android Studio 打开工程目录。

### 运行测试

```bash
./gradlew testDebugUnitTest   # 386 个单元测试（含 Robolectric Compose UI 冒烟测试）
./gradlew lintDebug           # 静态检查
```

## 使用说明

1. 安装后打开 App，按提示授予蓝牙相关权限；本机蓝牙名会自动设为 `PocketKeyboard-<品牌>`
2. 添加被控设备（二选一）：
   - 在 App 里点「添加被控设备」→ 从扫描列表里选择对端，App 直接发起配对；
   - 或在配对页点「可被发现」，然后**在被控设备（iPad / Mac / Windows 等）的蓝牙菜单里选择本设备**
3. 按提示完成配对（见下方「配对码说明」）；连接成功后设备行显示「已连接」
4. 首次连接时 App 会询问「这是苹果设备还是其他设备」，选择后自动适配键盘布局与触控板手势
5. 点 CONTROL 进入控制界面；五指短按随时返回键盘页面；设备行「断开 / 连接」按钮管理连接，左滑删除解除配对

### 配对码说明

配对页展示的数字就是**当次配对真实使用的数字**：

- **固定配对码 `0000`**（空闲时显示）：被控设备要求输入 PIN / 配对码时输入它；
- **配对进行中**：页面会实时显示系统生成的**真实 SSP 数字**——与被控设备屏幕上的一模一样，核对一致即可；
- 需要点击确认时（数字比较 / Just Works），在系统弹出的配对确认框里点「配对」。

> 平台限制说明：Android 将 `setPairingConfirmation` / `setPin` 收在 `BLUETOOTH_PRIVILEGED`（signature|privileged 权限）之后，普通应用无法代替用户完成最终确认——因此最后一步确认由系统配对框承接，但**数字的唯一来源是 App**（与对端显示严格一致）。

## 工程结构

```
app/src/main/java/com/pocketkeyboard/app/
├── MainActivity.kt          # 唯一 Activity：权限申请、五指挥势层、页面容器
├── hid/                     # 蓝牙 HID 后端：HidDeviceTransport（系统 profile）+ NullHidTransport 兜底
├── gesture/                 # 五指挥势引擎（五指短按）、手势仲裁器（滑动凑指窗口）、HUD、页面转场
├── keyboard/                # 87 键 / 26 键布局数据模型、无缝键帽、fn 组合功能、竖屏融合页、HID 报告引擎
├── trackpad/                # 触控板手势、Win/Apple 手势集、数字小键盘 sheet
└── ui/                      # MainViewModel、配对页、主题
app/src/test/                # 386 个单元测试（布局/手势/HID 报文/dock 策略/Robolectric UI）
```

详见代码内注释与 [跨模块契约](app/src/main/java/com/pocketkeyboard/app/hid/HidTransport.kt)。

## 已知限制

| # | 限制 | 现状 |
| --- | --- | --- |
| 1 | **配对最终确认需要系统框** | `setPairingConfirmation` / `setPin` 需要 `BLUETOOTH_PRIVILEGED`（Android 平台封锁普通应用）；App 展示的即真实 SSP 数字，最终在系统配对框点「配对」即可 |
| 2 | **多设备切换依赖 registry 已知地址** | 从未连接过的对端不会主动 `connectHost`，需先在配对页点选一次 |
| 3 | **HID 外设注册唯一性** | 系统同一时刻只允许一个 App 注册 HID 外设；若被其他输入法占用，注册被拒后回到前台会自动重试 |
| 4 | **注册楔死的 root 增强** | 部分 ROM（实测 MIUI）在进程被强杀后会卡死 HID 注册；普通设备提示开关蓝牙恢复，root 设备 App 会自动重启蓝牙栈自愈 |
| 5 | **`AppMode.NUMPAD` 不切换页面** | 小键盘是触控板页内的底部 sheet，不触发页面转场 |
| 6 | **测试覆盖边界** | 单元测试覆盖纯逻辑层；真实蓝牙连接、手势手感、震感档位需真机验证；暂无 androidTest |

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` / `BLUETOOTH_ADVERTISE` | Android 12+ 发现、连接键鼠接收端 |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | Android 11 及以下经典蓝牙 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Android 11 及以下扫描所需 |
| `POST_NOTIFICATIONS` | Android 13+ 显示连接状态通知 |

## 开源协议

[MIT License](LICENSE)
