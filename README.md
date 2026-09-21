# 口袋键鼠（PocketKeyboard）

把安卓手机变成**蓝牙键盘 + 蓝牙触控板**外设的极简 App：黑白主题、大面积极简、无缝键帽、苹果式设计语言、OLED 省电纯黑背景。

配对 iPad / iPhone / Mac / Windows 等设备后，手机即可作为它们的蓝牙键盘与触控板使用，支持同时与多台设备配对并按需切换控制目标。

**English version: [README.en.md](README.en.md)**

## 演示截图

| 配对页 | 键盘页（87 键 TKL） |
| --- | --- |
| ![配对页](docs/screenshots/01_pairing.png) | ![键盘页](docs/screenshots/02_keyboard.png) |

| 触控板页 | 数字小键盘 |
| --- | --- |
| ![触控板页](docs/screenshots/03_trackpad.png) | ![数字小键盘](docs/screenshots/04_numpad.png) |

## 功能特性

- **蓝牙 HID 外设**：基于系统 `BluetoothHidDevice` profile 注册键盘 + 触控板复合外设（含标准 HID 报告描述符），配对后被控设备将其识别为蓝牙键盘/触控板
- **配对页**：显示本机蓝牙名与配对码，支持同时与多台设备配对；首次连接某设备时选择「苹果设备 / 其他设备」以适配布局与手势（选择持久化，不重复询问）
- **CONTROL 按钮**：未连接任何设备时灰色不可用，连接成功后变黑可点击进入控制界面
- **五指挥势**（全局，任意页面生效，带动效与文字提示）：
  - 五指收缩 → 切换到触控板模式
  - 五指张开 → 切换到键盘模式
  - 五指整体左右滑动 → 在已配对设备间循环切换控制目标
- **87 键键盘页**：TKL 无小键盘区布局，苹果 / Windows 双套键位；黑底白字、键间无缝；按压有下陷视觉效果 + 触觉反馈
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
./gradlew testDebugUnitTest   # 159 个单元测试（含 Robolectric Compose UI 冒烟测试）
./gradlew lintDebug           # 静态检查
```

## 使用说明

1. 安装后打开 App，按提示授予蓝牙相关权限
2. 在配对页点右上角「可被发现」，然后**在被控设备（iPad / Mac / Windows 等）的蓝牙菜单里选择本手机**
3. 按系统配对流程完成配对（部分设备需要双方确认）；连接成功后 CONTROL 按钮变黑
4. 首次连接时 App 会询问「这是苹果设备还是其他设备」，选择后自动适配键盘布局与触控板手势
5. 点 CONTROL 进入控制界面；五指挥势随时切换模式 / 切换设备

> 说明：当前实现走经典蓝牙 HID profile，配对以系统 SSP 数字比对为准；配对页展示的 6 位数字用于支持走配对码输入的对端场景。

## 工程结构

```
app/src/main/java/com/pocketkeyboard/app/
├── MainActivity.kt          # 唯一 Activity：权限申请、五指挥势层、页面容器
├── hid/                     # 蓝牙 HID 后端：HidDeviceTransport（系统 profile）+ NullHidTransport 兜底
├── gesture/                 # 五指挥势引擎、手势仲裁器（60ms 窗口）、HUD、页面转场
├── keyboard/                # 87 键布局数据模型、无缝键帽、fn 组合功能、HID 报告引擎
├── trackpad/                # 触控板手势、Win/Apple 手势集、数字小键盘 sheet
└── ui/                      # MainViewModel、配对页、主题
app/src/test/                # 159 个单元测试（布局/手势/HID 报文/Robolectric UI）
```

详见代码内注释与 [跨模块契约](app/src/main/java/com/pocketkeyboard/app/hid/HidTransport.kt)。

## 已知限制

| # | 限制 | 现状 |
| --- | --- | --- |
| 1 | **不可用原因提示未接线** | 蓝牙关闭 / 注册被拒等原因文案已备好但 UI 未展示 |
| 2 | **多设备切换依赖 registry 已知地址** | 从未连接过的对端不会主动 `connectHost`，需先在配对页点选一次 |
| 3 | **HID 外设注册唯一性** | 系统同一时刻只允许一个 App 注册 HID 外设；若被其他输入法占用，注册被拒后回到前台会自动重试 |
| 4 | **配对码只是展示值** | 真实 SSP 数字以系统配对框为准 |
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
