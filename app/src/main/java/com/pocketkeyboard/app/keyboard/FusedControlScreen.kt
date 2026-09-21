package com.pocketkeyboard.app.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketkeyboard.app.R
import com.pocketkeyboard.app.gesture.GestureArbiter
import com.pocketkeyboard.app.hid.HidController
import com.pocketkeyboard.app.hid.HidTransport
import com.pocketkeyboard.app.hid.HidUsage
import com.pocketkeyboard.app.hid.HidUsageMapper
import com.pocketkeyboard.app.hid.MouseButton
import com.pocketkeyboard.app.trackpad.NumpadSheet
import com.pocketkeyboard.app.trackpad.NumpadToggleButton
import com.pocketkeyboard.app.trackpad.TrackpadClickZone
import com.pocketkeyboard.app.trackpad.TrackpadDivider
import com.pocketkeyboard.app.trackpad.TrackpadGestureHandler
import com.pocketkeyboard.app.trackpad.TrackpadHidActions
import com.pocketkeyboard.app.trackpad.TrackpadMetrics
import com.pocketkeyboard.app.trackpad.TrackpadThresholds
import com.pocketkeyboard.app.trackpad.clickZones
import com.pocketkeyboard.app.trackpad.trackpadGestures
import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.PairedDevice
import com.pocketkeyboard.app.ui.theme.PureBlack
import com.pocketkeyboard.app.ui.theme.PureWhite
import kotlin.math.roundToInt

/**
 * 竖屏融合控制页：上半触控板 + 下半 26 键手机输入法风格 QWERTY。
 *
 * 目录：app/src/main/java/com/pocketkeyboard/app/keyboard/
 *
 * ## 布局结构（竖屏）
 *
 * ```
 * ┌───────────────────────────────────────┐
 * │ [123]                                 │  ← 右上角小键盘开关（复用 NumpadSheet）
 * │            触控板区（1–4 指手势）        │  ← 复用 trackpad 包的手势引擎与仲裁
 * │              ─┐                        │
 * │           左  │  右                    │  ← 底部短竖线分割的左右点击区（仅 Windows 模式）
 * │         当前控制设备名                   │
 * ├───────────────────────────────────────┤
 * │  Q W E R T Y U I O P                  │  ← 26 键 QWERTY（三行字母 10/9/7）
 * │   A S D F G H J K L                   │
 * │    Z X C V B N M                      │
 * │  ⇧  ⌫    空格    return                │  ← 底部功能行（shift / 退格 / 空格 / 回车）
 * └───────────────────────────────────────┘
 * ```
 *
 * 竖屏下 KEYBOARD 与 TRACKPAD 两种 mode 都展示本页（五指收缩 / 张开照常切换 mode，
 * 但视觉不再变化，因为两边的布局已经融合）。
 *
 * ## 尺寸分配策略
 *
 * - 触控板区占 [FusedMetrics.TRACKPAD_HEIGHT_FRACTION]（0.42），26 键键盘占其余 0.58：
 *   触控板是「指向 / 滚动」的主战场，需要足够大的滑动行程；键盘四行等分剩余高度，
 *   每行约 0.58 × 屏高 / 4，在常见竖屏（约 870dp 高）上约 126dp/行，键帽高度充裕；
 * - 26 键键盘每行 u 总宽都是 [PhoneQwertyLayout.ROW_U]（10u）：第二 / 第三行字母用
 *   两侧留白（0.5u / 1.5u）把 9 / 7 颗字母居中对齐，行内按 weight 归一化填满屏宽，
 *   因此字母键宽三行一致、功能行与字母行宽度基准相同（Gboard 的同款做法）；
 * - 触控板区底部的左右点击区与分割线**全部复用触控板页的同一批组件**
 *   （[clickZones] 几何 + [TrackpadClickZone] 点击区 + [TrackpadDivider] 短竖线）：
 *   手势层判定与视觉画的是同一块区域，不会「看到的和摸到的」不一致；
 *   触控板工程师若调整该规格，本页自动跟随。
 *
 * ## 26 键 QWERTY 的参考来源（联网调研，2026-09）
 *
 * - **FlorisBoard**（github.com/florisboard/florisboard，Kotlin，约 6.7k star）：
 *   字母三行 + 独立功能行的结构、shift 的「按住生效 / 轻点粘滞」双模式、
 *   键帽以 u 比例描述宽度、行内按权重归一化填满屏宽的做法；
 * - **Simple Keyboard**（SimpleMobileTools，约 2k star）与 **AnySoftKeyboard**
 *   （AnySoftKeyboard/AnySoftKeyboard，Java，约 3.4k star）：手机输入法经典的
 *   三行字母居中、第二 / 第三行两侧留白、底部 shift / 退格 / 空格 / 回车一字排开的
 *   键位比例（letter 1u、shift 与退格各 1.5u、空格 4u、回车 3u）。
 *
 * 交互按输入法惯例实现为**原生版本**：shift 轻点粘滞一次（下一次字母自动大写并解锁）、
 * 按住 shift 再按字母为组合大写、再次轻点取消粘滞；退格 / 空格 / 回车均为单发 usage。
 *
 * ## 与其它模块的关系
 *
 * - 触控板手势层直接复用 trackpad 包的 `Modifier.trackpadGestures` /
 *   [TrackpadGestureTracker]（经 [TrackpadHidActions] 发 HID 报告），与触控板页
 *   完全同一套识别逻辑；本文件**不 import TrackpadScreen**（那是触控板工程师的页面）；
 * - 按键报告统一走 [KeyboardReportEngine]（修饰键位图 + 最多 6 键同按），与键盘页 /
 *   小键盘同一引擎，不新建发送链路；
 * - 键帽直接用键盘页的 [KeyCap]（无缝黑底白字、按压下陷 + 触觉），
 *   键帽颜色 / 字号 / 震感偏好沿用键盘页的 DataStore（fn+C / fn+S / fn+V 调过即生效）；
 * - 每个键帽挂 `Modifier.pocketKeyGestures`：≥5 指针时放行给底层五指挥势层，
 *   因此五指挥手在融合页同样有效（详见 gesture/KeyPointerInput.kt）。
 *
 * @param transport HID 传输实现。null 时本页自建 [HidController] 并随组合生命周期启停；
 *    若调用方统一持有 HidController（MainActivity 正是如此），把它传进来即可避免重复注册。
 */
@Composable
fun FusedControlScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel(),
    arbiter: GestureArbiter = remember { GestureArbiter() },
    transport: HidTransport? = null,
) {
    val activeDevice by viewModel.activeDevice.collectAsStateWithLifecycle()
    val platform = activeDevice?.platform ?: DevicePlatform.OTHER

    val context = LocalContext.current

    // 键盘页偏好（颜色 / 字号 / 震感）：与键盘页、小键盘同一份 DataStore，
    // 否则 fn+C / fn+S 调过的偏好对本页不生效
    val preferencesStore = remember { KeyboardPreferencesStore(context) }
    val preferences by preferencesStore.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())

    // 震感档位同步到单例：本页可能是用户进入 App 后的第一页（竖屏 KEYBOARD mode 默认就是本页）
    LaunchedEffect(preferences.hapticStrength) {
        HapticScale.set(preferences.hapticStrength)
    }

    // 传输：调用方未提供时自建（启动 / 停止随本页组合生命周期）
    val effectiveTransport = transport ?: rememberFusedTransport(viewModel)

    // 键盘报告引擎：26 键键盘与小键盘 sheet 共用同一引擎类型（各自一个实例，
    // 修饰键位图互不影响；页面退出时统一 releaseAll 避免卡键）
    val keyboardEngine = remember(effectiveTransport) { KeyboardReportEngine(effectiveTransport) }
    DisposableEffect(keyboardEngine) {
        onDispose { keyboardEngine.releaseAll() }
    }

    // 触控板手势 → HID：与触控板页同一翻译层
    val trackpadActions = remember(effectiveTransport) { TrackpadHidActions(effectiveTransport) }
    val trackpadHandler = remember(trackpadActions) {
        TrackpadGestureHandler { gesture -> trackpadActions.dispatch(gesture) }
    }

    // px 级阈值：与触控板页同一份换算（触摸 slop / 三指挥手距离）
    val thresholds = with(LocalDensity.current) {
        TrackpadThresholds(
            tapSlopPx = TrackpadMetrics.TAP_SLOP.toPx(),
            swipeDistancePx = TrackpadMetrics.SWIPE_DISTANCE.toPx(),
        )
    }

    // 小键盘 sheet 开关：只在用户显式操作（点右上角开关 / 点遮罩 / 按返回键）时变化，
    // 与触控板页约定一致——控制设备变化不被动收起，避免打断连续录入
    var numpadVisible by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            FusedTrackpadArea(
                platform = platform,
                device = activeDevice,
                handler = trackpadHandler,
                arbiter = arbiter,
                thresholds = thresholds,
                transport = effectiveTransport,
                onNumpadToggle = { numpadVisible = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(FusedMetrics.TRACKPAD_HEIGHT_FRACTION),
            )
            FusedPhoneKeyboard(
                engine = keyboardEngine,
                textColor = preferences.keyCapColor.toKeyCapTextColor(),
                textSize = preferences.keyTextSize,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f - FusedMetrics.TRACKPAD_HEIGHT_FRACTION),
            )
        }

        // 数字小键盘 sheet（从底部滑入）：键帽颜色 / 字号沿用键盘页偏好
        NumpadSheet(
            visible = numpadVisible,
            transport = effectiveTransport,
            textColor = preferences.keyCapColor.toKeyCapTextColor(),
            textSize = preferences.keyTextSize,
            onDismiss = { numpadVisible = false },
        )
    }
}

/** 融合页的尺寸常量（dp / 比例）。UI 布局与手势几何共用，避免两边算出的区域不一致。 */
internal object FusedMetrics {

    /** 触控板区高度占融合页的比例；其余比例给 26 键键盘。 */
    const val TRACKPAD_HEIGHT_FRACTION: Float = 0.42f
}

/**
 * 融合页上半部分：触控板区。
 *
 * 结构（与触控板页的改造规格保持一致）：
 * - 右上角「123」小键盘开关（复用 trackpad 包的 [NumpadToggleButton]）；
 * - 中部大面积触控区，挂 `Modifier.trackpadGestures`（1–4 指手势 + 与五指挥势层仲裁）；
 * - 底部（仅 Windows 模式）左右两个点击区，中间用一条短竖线分割；
 * - 最底部一条设备名（本页触控区不是屏幕最底部，系统手势条由下方的键盘区避让，
 *   因此设备名放触控区底部即可，不占用手势行程）。
 */
@Composable
private fun FusedTrackpadArea(
    platform: DevicePlatform,
    device: PairedDevice?,
    handler: TrackpadGestureHandler,
    arbiter: GestureArbiter,
    thresholds: TrackpadThresholds,
    transport: HidTransport,
    onNumpadToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val physicalButtons = platform == DevicePlatform.OTHER

    BoxWithConstraints(
        modifier = modifier
            .background(PureBlack)
            .trackpadGestures(
                platform = platform,
                handler = handler,
                arbiter = arbiter,
                thresholds = thresholds,
                clickZonesEnabled = physicalButtons,
            ),
    ) {
        if (physicalButtons) {
            // 与手势层完全相同的函数算出点击带矩形：画出来的和判定的是同一块区域
            val density = LocalDensity.current
            val zones = remember(maxWidth, maxHeight, density) {
                clickZones(
                    widthPx = with(density) { maxWidth.toPx() },
                    heightPx = with(density) { maxHeight.toPx() },
                    density = density,
                )
            }

            TrackpadClickZone(
                modifier = Modifier
                    .offset {
                        IntOffset(zones.left.left.roundToInt(), zones.left.top.roundToInt())
                    }
                    .size(
                        width = with(density) { zones.left.width.toDp() },
                        height = with(density) { zones.left.height.toDp() },
                    ),
                onPress = { transport.sendMouseButton(MouseButton.LEFT, true) },
                onRelease = { transport.sendMouseButton(MouseButton.LEFT, false) },
            )
            TrackpadClickZone(
                modifier = Modifier
                    .offset {
                        IntOffset(zones.right.left.roundToInt(), zones.right.top.roundToInt())
                    }
                    .size(
                        width = with(density) { zones.right.width.toDp() },
                        height = with(density) { zones.right.height.toDp() },
                    ),
                onPress = { transport.sendMouseButton(MouseButton.RIGHT, true) },
                onRelease = { transport.sendMouseButton(MouseButton.RIGHT, false) },
            )
            // 一条短竖线：左右点击分割的视觉分界（自身不拦截触摸，对触摸完全透明）。
            // 复用触控板包的 TrackpadDivider：融合页与触控板页画出来的永远是同一条线
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(zones.band.left.roundToInt(), zones.band.top.roundToInt())
                    }
                    .size(
                        width = with(density) { zones.band.width.toDp() },
                        height = with(density) { zones.band.height.toDp() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                TrackpadDivider()
            }
        }

        // 右上角：小键盘开关（复用触控板页同一组件，视觉与行为一致）。
        // statusBarsPadding：开关贴在屏幕顶缘，不加状态栏内边距时按钮上半截被状态栏
        // 盖住，MIUI 会拦截状态栏区域内的触摸（真机实测：点在按钮上部无响应），
        // 加一层内边距让整个按钮落在可触摸区域内
        NumpadToggleButton(
            onClick = onNumpadToggle,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp),
        )

        // 触控区底部：当前控制设备名（不加 pointerInput，对触摸完全透明）
        FusedDeviceNameStrip(
            device = device,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 融合页触控区底部：当前控制设备名（无设备时显示未连接提示）。对触摸完全透明。 */
@Composable
private fun FusedDeviceNameStrip(
    device: PairedDevice?,
    modifier: Modifier = Modifier,
) {
    val platformLabel = stringResource(
        if (device?.platform == DevicePlatform.APPLE) {
            R.string.pairing_device_platform_apple
        } else {
            R.string.pairing_device_platform_other
        }
    )
    val text = if (device == null) {
        stringResource(R.string.trackpad_no_device)
    } else {
        stringResource(R.string.trackpad_active_device_format, device.name, platformLabel)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TrackpadMetrics.SAFE_ZONE_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = PureWhite.copy(alpha = 0.45f),
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

/**
 * 融合页下半部分：26 键手机输入法风格 QWERTY。
 *
 * 三行字母（10/9/7，行内两侧留白居中）+ 底部功能行（shift / 退格 / 空格 / 回车）。
 * 每行 u 总宽都是 [PhoneQwertyLayout.ROW_U]，行内按 weight 归一化填满屏宽。
 *
 * ## shift 行为（输入法惯例）
 *
 * - 轻点 shift → 粘滞：下一次字母发送大写（`needsShift`），用完立即解锁；
 * - 按住 shift 不放、再按字母 → 组合大写（修饰键位图），抬手后不粘滞；
 * - 粘滞状态下再轻点 shift → 取消粘滞；
 * - 被五指挥势作废时整体复位（补发松键，避免卡在 shift 上）。
 *
 * 状态机是纯 Kotlin 的 [FusedShiftState]，可在 JVM 单测里逐条验证。
 */
@Composable
private fun FusedPhoneKeyboard(
    engine: KeyboardReportEngine,
    textColor: Color,
    textSize: KeyTextSize,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val vibrator = rememberFusedVibrator()

    val rows = remember { PhoneQwertyLayout.rows() }
    val shiftState = remember { FusedShiftState() }
    var shiftActive by remember { mutableStateOf(false) }

    // 页面退出（切模式 / 切页）时把 shift 状态与按住的键一起复位，避免卡键
    DisposableEffect(engine) {
        onDispose {
            shiftState.reset()
            engine.releaseAll()
        }
    }

    fun onKeyDown(spec: KeySpec) {
        performKeyHaptic(view, vibrator)
        when (val action = spec.action) {
            is KeyAction.Character -> {
                val char = shiftState.charToSend(action.char)
                shiftActive = shiftState.active
                HidUsageMapper.usageForChar(char)?.let { mapping ->
                    engine.keyDown(mapping.usage, mapping.needsShift)
                }
            }

            is KeyAction.Usage -> engine.keyDown(action.usage)

            is KeyAction.Modifier -> {
                if (action.usage == HidUsage.KEY_LEFT_SHIFT) {
                    shiftState.onShiftDown()
                    shiftActive = true
                }
                engine.modifierDown(action.usage)
            }

            // 26 键布局没有 F 行 / fn 键，穷尽分支防御
            is KeyAction.FunctionKey -> Unit
            KeyAction.Fn -> Unit
        }
    }

    fun onKeyUp(spec: KeySpec) {
        when (val action = spec.action) {
            is KeyAction.Character ->
                HidUsageMapper.usageForChar(action.char)?.let { engine.keyUp(it.usage) }

            is KeyAction.Usage -> engine.keyUp(action.usage)

            is KeyAction.Modifier -> {
                engine.modifierUp(action.usage)
                if (action.usage == HidUsage.KEY_LEFT_SHIFT) {
                    shiftState.onShiftUp()
                    shiftActive = shiftState.active
                }
            }

            is KeyAction.FunctionKey -> Unit
            KeyAction.Fn -> Unit
        }
    }

    fun onKeyAbandoned(spec: KeySpec) {
        // 五指挥势作废本次按下：补发松键；shift 还要额外复位粘滞 / 按住状态
        when (val action = spec.action) {
            KeyAction.Fn -> Unit
            is KeyAction.Modifier -> {
                engine.modifierUp(action.usage)
                if (action.usage == HidUsage.KEY_LEFT_SHIFT) {
                    shiftState.reset()
                    shiftActive = false
                }
            }

            is KeyAction.Character ->
                HidUsageMapper.usageForChar(action.char)?.let { engine.keyUp(it.usage) }

            is KeyAction.Usage -> engine.keyUp(action.usage)
            is KeyAction.FunctionKey -> Unit
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // 第二 / 第三行字母两侧的留白：把 9 / 7 颗字母居中对齐（Gboard 同款）
                if (row.sideMarginU > 0f) {
                    Spacer(
                        modifier = Modifier
                            .weight(row.sideMarginU)
                            .fillMaxHeight(),
                    )
                }
                row.keys.forEach { spec ->
                    KeyCap(
                        spec = spec,
                        // shift 粘滞 / 按住时，shift 键帽（style = FN）整颗高亮
                        fnActive = shiftActive && spec.action is KeyAction.Modifier &&
                            spec.action.usage == HidUsage.KEY_LEFT_SHIFT,
                        textColor = textColor,
                        textSize = textSize,
                        modifier = Modifier
                            .weight(spec.widthU)
                            .fillMaxHeight(),
                        onPress = { onKeyDown(spec) },
                        onRelease = { onKeyUp(spec) },
                        onAbandoned = { onKeyAbandoned(spec) },
                    )
                }
                if (row.sideMarginU > 0f) {
                    Spacer(
                        modifier = Modifier
                            .weight(row.sideMarginU)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * 26 键 QWERTY 的键位数据模型（纯 Kotlin，无 Compose / Android 依赖）。
 *
 * 布局（每行 u 总宽都是 [ROW_U]，渲染时按行内 weight 归一化填满屏宽）：
 *
 * ```
 * 行 1（10u）：Q W E R T Y U I O P
 * 行 2（10u）：0.5u 留白 + A S D F G H J K L + 0.5u 留白
 * 行 3（10u）：1.5u 留白 + Z X C V B N M + 1.5u 留白
 * 行 4（10u）：shift(1.5u) ⌫(1.5u) space(4u) return(3u)
 * ```
 *
 * 字母键的 [KeyAction.Character] 一律用小写字符（发送链路由 `HidUsageMapper` 决定
 * 是否需要 Shift，与显示层的大写标签解耦）；shift 粘滞时经 [FusedShiftState] 换成大写。
 */
object PhoneQwertyLayout {

    /** 每行 u 总宽（三行字母 + 功能行一致，保证键宽基准相同）。 */
    const val ROW_U: Float = 10f

    /** 第二行两侧留白（0.5u × 2 + 9 × 1u = 10u）。 */
    const val TOP_ROW_MARGIN_U: Float = 0.5f

    /** 第三行两侧留白（1.5u × 2 + 7 × 1u = 10u）。 */
    const val BOTTOM_ROW_MARGIN_U: Float = 1.5f

    /** shift / 退格宽（Gboard 同款 1.5u）。 */
    const val SHIFT_U: Float = 1.5f

    const val BACKSPACE_U: Float = 1.5f

    /** 空格宽。 */
    const val SPACE_U: Float = 4f

    /** 回车宽。 */
    const val ENTER_U: Float = 3f

    /** 4 行键位（行 1 → 行 4）。 */
    fun rows(): List<PhoneKeyRow> = listOf(
        // 行 1：qwertyuiop
        PhoneKeyRow(
            keys = letterKeys("qwertyuiop"),
            sideMarginU = 0f,
        ),
        // 行 2：asdfghjkl（两侧 0.5u 留白居中）
        PhoneKeyRow(
            keys = letterKeys("asdfghjkl"),
            sideMarginU = TOP_ROW_MARGIN_U,
        ),
        // 行 3：zxcvbnm（两侧 1.5u 留白居中）
        PhoneKeyRow(
            keys = letterKeys("zxcvbnm"),
            sideMarginU = BOTTOM_ROW_MARGIN_U,
        ),
        // 行 4：shift / 退格 / 空格 / 回车
        PhoneKeyRow(
            keys = listOf(shiftKey(), backspaceKey(), spaceKey(), enterKey()),
            sideMarginU = 0f,
        ),
    )

    private fun letterKeys(letters: String): List<KeySpec> =
        letters.map { char ->
            KeySpec(
                labelRes = letterLabelRes(char),
                action = KeyAction.Character(char),
            )
        }

    /**
     * 字母 → 键帽文案资源。
     *
     * 显示层一律大写（strings.xml 里 key_q = "Q"…），发送层用的是 [KeyAction.Character]
     * 的小写字符，两边解耦：改显示不影响 `HidUsageMapper` 的输入映射。
     */
    private fun letterLabelRes(char: Char): Int = when (char) {
        'q' -> R.string.key_q
        'w' -> R.string.key_w
        'e' -> R.string.key_e
        'r' -> R.string.key_r
        't' -> R.string.key_t
        'y' -> R.string.key_y
        'u' -> R.string.key_u
        'i' -> R.string.key_i
        'o' -> R.string.key_o
        'p' -> R.string.key_p
        'a' -> R.string.key_a
        's' -> R.string.key_s
        'd' -> R.string.key_d
        'f' -> R.string.key_f
        'g' -> R.string.key_g
        'h' -> R.string.key_h
        'j' -> R.string.key_j
        'k' -> R.string.key_k
        'l' -> R.string.key_l
        'z' -> R.string.key_z
        'x' -> R.string.key_x
        'c' -> R.string.key_c
        'v' -> R.string.key_v
        'b' -> R.string.key_b
        'n' -> R.string.key_n
        'm' -> R.string.key_m
        else -> error("26 键布局不支持的字母: $char")
    }

    /**
     * shift 键：style = FN 是为了复用 [KeyCap] 的「fn 活跃时整颗高亮」视觉，
     * 这里高亮条件换成 shift 粘滞 / 按住（见 FusedPhoneKeyboard 的 fnActive 实参）。
     */
    private fun shiftKey(): KeySpec = KeySpec(
        labelRes = R.string.key_shift_l,
        widthU = SHIFT_U,
        action = KeyAction.Modifier(HidUsage.KEY_LEFT_SHIFT),
        style = KeyStyle.FN,
        contentDescRes = R.string.key_shift_l,
    )

    private fun backspaceKey(): KeySpec = KeySpec(
        labelRes = R.string.key_backspace,
        widthU = BACKSPACE_U,
        action = KeyAction.Usage(HidUsage.KEY_BACKSPACE),
        style = KeyStyle.MODIFIER,
        contentDescRes = R.string.key_backspace,
    )

    private fun spaceKey(): KeySpec = KeySpec(
        labelRes = R.string.key_space,
        widthU = SPACE_U,
        action = KeyAction.Usage(HidUsage.KEY_SPACE),
        style = KeyStyle.MODIFIER,
        contentDescRes = R.string.key_space,
    )

    private fun enterKey(): KeySpec = KeySpec(
        labelRes = R.string.key_enter,
        widthU = ENTER_U,
        action = KeyAction.Usage(HidUsage.KEY_ENTER),
        style = KeyStyle.MODIFIER,
        contentDescRes = R.string.key_enter,
    )
}

/**
 * 26 键键盘的一行：[keys] 从左到右排列，[sideMarginU] 为两侧留白（u）。
 */
data class PhoneKeyRow(
    val keys: List<KeySpec>,
    val sideMarginU: Float,
) {

    /** 该行 u 总宽（含两侧留白；四行都等于 [PhoneQwertyLayout.ROW_U]）。 */
    val totalU: Float
        get() = keys.sumOf { it.widthU.toDouble() }.toFloat() + sideMarginU * 2f
}

/**
 * 26 键键盘 shift 键的状态机（纯 Kotlin，可单测）。
 *
 * 规则（输入法惯例，参考 FlorisBoard 的 shift 双模式）：
 * - 按下 shift：进入「按住」态，修饰键位图置位（由调用方发 `modifierDown`）；
 * - 按住期间按过其它键 → 本次是组合，抬手后**不**粘滞；
 * - 按住期间没按过其它键 → 视为轻点，抬手时翻转粘滞态；
 * - 粘滞态下按字母：发送大写并立刻清除粘滞（单次生效）；
 * - 再次轻点 shift：翻转粘滞态（取消）。
 */
internal class FusedShiftState {

    private var held = false
    private var latched = false
    private var comboUsed = false

    /** shift 当前是否活跃（按住或粘滞）：用于键帽高亮。 */
    val active: Boolean
        get() = held || latched

    /** shift 是否处于粘滞（轻点）态。 */
    val latchedNow: Boolean
        get() = latched

    /** shift 按下：调用方应同时发送 `modifierDown(LEFT_SHIFT)`。 */
    fun onShiftDown() {
        held = true
        comboUsed = false
    }

    /**
     * shift 抬起：调用方应同时发送 `modifierUp(LEFT_SHIFT)`；返回抬起后的粘滞态。
     *
     * 未处于按住态时是防御分支（例如信号丢失导致的孤立抬手）：直接返回当前粘滞态，
     * 不翻转、不改状态。
     */
    fun onShiftUp(): Boolean {
        if (!held) return latched
        held = false
        if (!comboUsed) {
            // 轻点（按住期间没有组合过其它键）→ 翻转粘滞
            latched = !latched
        }
        comboUsed = false
        return latched
    }

    /**
     * 其它键按下时调用：返回该键实际应发送的字符。
     *
     * - shift 按住 → 大写（与 modifierDown 的位图叠加，双保险）；
     * - shift 粘滞 → 大写并清除粘滞（单次组合用完即清）；
     * - 都没有 → 原字符。
     */
    fun charToSend(char: Char): Char {
        val upper = active
        if (held) comboUsed = true
        if (latched) latched = false
        return if (upper) char.uppercaseChar() else char
    }

    /** 五指挥势作废按下 / 页面退出时整体复位。 */
    fun reset() {
        held = false
        latched = false
        comboUsed = false
    }
}

/** 融合页自建 HID 传输：调用方没有传入 [HidTransport] 时组装系统实现（或空实现）。 */
@Composable
private fun rememberFusedTransport(viewModel: MainViewModel): HidTransport {
    val context = LocalContext.current
    val controller = remember { HidController(context.applicationContext, viewModel) }
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.stop() }
    }
    return controller.transport
}

/** 取系统 Vibrator（API 31+ 走 VibratorManager）。 */
@Composable
private fun rememberFusedVibrator(): android.os.Vibrator? {
    val context = LocalContext.current
    return remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE)
                as? android.os.Vibrator
        }
    }
}
