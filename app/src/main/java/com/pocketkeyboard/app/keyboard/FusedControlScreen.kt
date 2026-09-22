package com.pocketkeyboard.app.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketkeyboard.app.R
import com.pocketkeyboard.app.gesture.GestureArbiter
import com.pocketkeyboard.app.gesture.LocalFiveFingerGate
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
import com.pocketkeyboard.app.ui.AppMode
import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.PairedDevice
import com.pocketkeyboard.app.ui.theme.PureBlack
import com.pocketkeyboard.app.ui.theme.PureWhite
import kotlin.math.roundToInt

/**
 * 竖屏融合控制页（Bug 6 改造后）：上半触控板 + 中部系统输入法唤起区 + 底部可锁定修饰键排。
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
 * ├───────────────────────────────────────┤  ← 居中淡出的细分隔线
 * │      键盘区：系统输入法唤起区（按 mode 变高矮）  │  ← 不可见 BasicTextField
 * ├───────────────────────────────────────┤  ← 居中淡出的细分隔线
 * │ ctrl shift fn win/⌘ alt tab esc        │  ← 可锁定修饰键排（点击锁定 / 再点解锁）
 * └───────────────────────────────────────┘
 * ```
 *
 * 竖屏下 KEYBOARD 与 TRACKPAD 两种 mode 都展示本页，但**视觉必须可区分**（B4，见下）。
 * 横屏仍是全屏 87 键 TKL（[KeyboardScreen]）与全屏触控板（trackpad 包的 TrackpadScreen），
 * 本页不参与。
 *
 * ## 两个 mode 的视觉区分（B4，真机实测第三轮）
 *
 * 五指张开 → 键盘模式：键盘区权重增大（触控板区缩小）+ **自动弹出系统输入法**；
 * 五指收缩 → 触控板模式：触控板区权重增大（键盘区缩成一条、只剩修饰键排）+ **收起系统输入法**。
 * mode 切换本身仍走 `MainViewModel.mode` 与既有 HUD 文案（MainActivity 的
 * `PocketGestureHandler`），本页只负责「mode 变了之后视觉与输入法怎么跟着变」。
 *
 * 权重定义见 [FusedLayout]，mode → 视觉 / 输入法的映射见 [fusedTrackpadWeight] 与
 * [FusedImeInputArea]。
 *
 * @param mode 当前页面模式。**必须传 `AnimatedContent` 的 `currentMode`**（而不是直接读
 *     `viewModel.mode`）：转场期间退场的那一页要保留自己的 mode，否则它会在退场动画里
 *     重复触发新 mode 的输入法弹出 / 权重变化
 *
 * ## 为什么不再自己做 26 键 QWERTY（Bug 6）
 *
 * 自研的 26 键手机布局在真机上被系统输入法全面压制：没有中文、没有 Emoji、没有
 * 联想纠错、符号页残缺。改造后字符输入**完全交给手机系统输入法**：本页只在中间
 * 放一个不可见的 `BasicTextField` 作为「文本入口」，用户点它弹出系统输入法，
 * 输入内容经 InputConnection 落到文本框后，由 [ImeTextDiff] 差分出「追加 / 删除」，
 * 再逐字符经 `HidUsageMapper` 映射成 HID usage 发给被控设备（输入框随即清空）。
 *
 * ## 输入法弹出时的避让
 *
 * 页面根部挂 `Modifier.imePadding()`（`WindowInsets.ime`，API 30+ 跟随输入法
 * 显示 / 隐藏动画同步插值）：输入法弹出时整页内容被顶到输入法上方，触控板
 * **不会被输入法盖掉**；输入法收起后触控板恢复完整高度。
 *
 * ## 与其它模块的关系
 *
 * - 触控板手势层直接复用 trackpad 包的 `Modifier.trackpadGestures` /
 *   [TrackpadGestureTracker]（经 [TrackpadHidActions] 发 HID 报告），与触控板页
 *   完全同一套识别逻辑；本文件**不 import TrackpadScreen**（那是触控板工程师的页面）；
 * - 按键报告统一走 [KeyboardReportEngine]（修饰键位图 + 最多 6 键同按），与键盘页 /
 *   小键盘同一引擎，不新建发送链路；IME 转发路径走 `keyTap`（一次性按下 + 松开）；
 * - fn 组合（fn + 空格 / C / S / V）与横屏 87 键共用 [FnComboActions]：锁定 fn 后
 *   经系统输入法打出的空格 / c / s / v 触发的是同一批本机功能（背光 / 键帽颜色 /
 *   字号 / 震感），不发给被控设备；
 * - 键帽颜色 / 字号 / 震感偏好沿用键盘页的 DataStore（fn+C / fn+S / fn+V 调过即生效）。
 *
 * @param transport HID 传输实现。null 时本页自建 [HidController] 并随组合生命周期启停；
 *    若调用方统一持有 HidController（MainActivity 正是如此），把它传进来即可避免重复注册。
 */
@Composable
fun FusedControlScreen(
    modifier: Modifier = Modifier,
    mode: AppMode,
    viewModel: MainViewModel = viewModel(),
    arbiter: GestureArbiter = remember { GestureArbiter() },
    transport: HidTransport? = null,
) {
    val activeDevice by viewModel.activeDevice.collectAsStateWithLifecycle()
    val platform = activeDevice?.platform ?: DevicePlatform.OTHER

    // 竖屏两种 mode 的视觉必须可区分（B4）：KEYBOARD / NUMPAD = 键盘模式——键盘区（系统
    // 输入法唤起区）权重增大、自动弹出系统输入法；TRACKPAD = 触控板模式——触控板区权重
    // 增大、键盘区缩到一条、系统输入法收起。五指收缩 / 张开切换的就是这个布尔量，
    // 因此「张开看到键盘、收缩看到触控板」在竖屏同样成立（真机实测原两种 mode 视觉
    // 完全相同，用户完全看不出模式切没切）。
    val keyboardMode = mode == AppMode.KEYBOARD || mode == AppMode.NUMPAD

    val context = LocalContext.current
    val view = LocalView.current

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

    // 键盘报告引擎：修饰键排与 IME 转发共用同一引擎（各自一个实例，
    // 修饰键位图互不影响；页面退出时统一 releaseAll 避免卡键）
    val keyboardEngine = remember(effectiveTransport) { KeyboardReportEngine(effectiveTransport) }

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

    // 可锁定修饰键的锁定集合（纯函数状态机见 [FusedModifierLatch]）
    var lockedModifiers by remember { mutableStateOf(emptySet<LockableModifier>()) }

    // fn 组合的本机功能（与横屏 87 键键盘页同一实现）
    val scope = rememberCoroutineScope()
    val fnComboLabels = rememberFnComboLabels()
    var hud by remember { mutableStateOf<KeyboardHudRequest?>(null) }
    val fnCombos = remember(context, preferencesStore, scope, fnComboLabels) {
        FnComboActions(
            context = context,
            store = preferencesStore,
            scope = scope,
            labels = fnComboLabels,
            currentPreferences = { preferences },
            onHud = { request -> hud = request },
        )
    }

    val vibrator = rememberFusedVibrator()
    val keyCapColor = preferences.keyCapColor.toKeyCapTextColor()

    // 页面退出（切模式 / 切页）时松开所有按住的键并解锁修饰键，避免卡键
    DisposableEffect(keyboardEngine) {
        onDispose {
            keyboardEngine.releaseAll()
            lockedModifiers = emptySet()
        }
    }

    // ---------------------------------------------------------------- IME 转发 → HID 报告

    /**
     * 系统输入法提交了一个字符：
     * 1. fn 锁定中 → 先试 fn 组合（本机功能，命中即清除 fn 锁定，与键盘页单次组合同理）；
     * 2. 否则经 `HidUsageMapper` 查 usage，叠加**锁定的修饰键**位图一次性按下 + 松开；
     * 3. 不可映射字符（中文、Emoji 等）直接丢弃——HID 键盘页面没有对应 usage。
     */
    fun onImeCommit(char: Char) {
        if (FusedModifierLatch.fnActive(lockedModifiers)) {
            if (fnCombos.run(char)) {
                lockedModifiers = FusedModifierLatch.withoutFn(lockedModifiers)
                performKeyHaptic(view, vibrator)
                return
            }
            // fn + 其它字符：粘滞的 fn 只生效一次组合，清除后按普通字符发送
            lockedModifiers = FusedModifierLatch.withoutFn(lockedModifiers)
        }
        val mapping = HidUsageMapper.usageForChar(char) ?: return
        keyboardEngine.keyTap(
            usage = mapping.usage,
            extraModifiers = FusedModifierLatch.modifierBits(lockedModifiers),
            momentaryShift = mapping.needsShift,
        )
    }

    /** 输入框文本缩短：按字符数补发退格（锁定的修饰键一并叠加，如 Ctrl+退格删词）。 */
    fun onImeBackspace(count: Int) {
        val bits = FusedModifierLatch.modifierBits(lockedModifiers)
        repeat(count) {
            keyboardEngine.keyTap(usage = HidUsage.KEY_BACKSPACE, extraModifiers = bits)
        }
    }

    /** 修饰键点击：切换锁定态（带触觉，视觉高亮由 [lockedModifiers] 驱动重组）。 */
    fun onToggleModifier(modifier: LockableModifier) {
        performKeyHaptic(view, vibrator)
        lockedModifiers = FusedModifierLatch.toggle(lockedModifiers, modifier)
    }

    /** tab / esc 点击：直接发送 usage（不锁定；锁定的修饰键照常叠加）。 */
    fun onDirectAction(action: KeyAction) {
        performKeyHaptic(view, vibrator)
        val usage = (action as? KeyAction.Usage)?.usage ?: return
        keyboardEngine.keyTap(
            usage = usage,
            extraModifiers = FusedModifierLatch.modifierBits(lockedModifiers),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            // 需求 6：系统输入法弹出时把整页内容顶到输入法上方（WindowInsets.ime，
            // API 30+ 跟随输入法显示 / 隐藏动画插值），触控板不会被输入法盖掉。
            // **imePadding 必须排在 statusBarsPadding 之前**（modifier 链上更靠外）：
            // Compose 的 WindowInsets.ime 值会扣掉已被消费的状态栏高度（真机实测差
            // 一个状态栏高 80px），ime 在最外层按「窗口坐标」取值才不会少算，
            // 否则输入法弹出时修饰键排会陷进输入法窗口下、点不到
            .imePadding()
            // 需求 1：页面根部预留状态栏 / 刘海空间（触控板右上角开关、设备名条都不被遮挡）；
            // edge-to-edge 保留（纯黑背景仍延伸至屏幕边缘）
            .statusBarsPadding()
            .displayCutoutPadding(),
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
                    // B4：触控板区 / 键盘区的权重随 mode 变化——键盘模式键盘区更大
                    // （配合弹出的系统输入法），触控板模式触控板区更大（键盘区缩成一条）
                    .weight(fusedTrackpadWeight(keyboardMode)),
            )
            FusedImeInputArea(
                onCommitChar = ::onImeCommit,
                onBackspace = ::onImeBackspace,
                keyboardMode = keyboardMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(fusedKeyboardAreaWeight(keyboardMode)),
            )
            FusedModifierRow(
                platform = platform,
                locked = lockedModifiers,
                onToggleModifier = ::onToggleModifier,
                onDirectAction = ::onDirectAction,
                textColor = keyCapColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FusedMetrics.MODIFIER_ROW_HEIGHT),
            )
        }

        // 数字小键盘 sheet（从底部滑入）：键帽颜色 / 字号沿用键盘页偏好
        NumpadSheet(
            visible = numpadVisible,
            transport = effectiveTransport,
            textColor = keyCapColor,
            textSize = preferences.keyTextSize,
            onDismiss = { numpadVisible = false },
        )

        // fn 组合 HUD（屏幕中央、iOS 风格）：与横屏 87 键同一组件；对触摸完全透明
        KeyboardHudLayer(
            hud = hud,
            onDismiss = { hud = null },
        )
    }
}

/** 融合页的尺寸常量（dp / 比例）。UI 布局与手势几何共用，避免两边算出的区域不一致。 */
internal object FusedMetrics {

    /** 可锁定修饰键排高度（固定值：不随 mode 权重变化，两个 mode 下都可锁定修饰键）。 */
    val MODIFIER_ROW_HEIGHT = 64.dp
}

/**
 * 触控板区在竖屏融合页 Column 里的权重（B4）。
 *
 * 键盘模式（[keyboardMode] = true，五指张开 / KEYBOARD / NUMPAD）：键盘区（系统输入法唤起区）
 * 拿到主要高度，触控板区相应缩小——用户要做的是「打字」而不是「指向」，屏幕下半还有
 * 弹出的系统输入法。触控板模式则反过来：触控板区拿到绝大部分高度，键盘区缩成一条
 * （仅留修饰键排）。
 */
internal fun fusedTrackpadWeight(keyboardMode: Boolean): Float = when (keyboardMode) {
    // 0.55 + 0.45 = 1：两个 weight 之和，余下高度全给固定高度的修饰键排
    true -> FusedLayout.WEIGHT_TRACKPAD_KEYBOARD_MODE
    false -> FusedLayout.WEIGHT_TRACKPAD_TRACKPAD_MODE
}

/** 键盘区（系统输入法唤起区）的权重，见 [fusedTrackpadWeight]。 */
internal fun fusedKeyboardAreaWeight(keyboardMode: Boolean): Float = when (keyboardMode) {
    true -> FusedLayout.WEIGHT_KEYBOARD_AREA_KEYBOARD_MODE
    false -> FusedLayout.WEIGHT_KEYBOARD_AREA_TRACKPAD_MODE
}

/**
 * 竖屏融合页两个 mode 的布局权重（B4，单测覆盖）。
 *
 * 权重之和必须为 1（Column 里与固定高度的修饰键排共同分完剩余高度），且两个 mode
 * **必须**不同——这是「竖屏两个 mode 视觉可区分」的结构性保证。
 */
internal object FusedLayout {
    const val WEIGHT_TRACKPAD_KEYBOARD_MODE = 0.45f
    const val WEIGHT_KEYBOARD_AREA_KEYBOARD_MODE = 0.55f
    const val WEIGHT_TRACKPAD_TRACKPAD_MODE = 0.82f
    const val WEIGHT_KEYBOARD_AREA_TRACKPAD_MODE = 0.18f
}

/**
 * 融合页上半部分：触控板区。
 *
 * 结构（与触控板页的改造规格保持一致）：
 * - 右上角「123」小键盘开关（复用 trackpad 包的 [NumpadToggleButton]）；
 * - 中部大面积触控区，挂 `Modifier.trackpadGestures`（1–4 指手势 + 与五指挥势层仲裁）；
 * - 底部（仅 Windows 模式）左右两个点击区，中间用一条短竖线分割；
 * - 最底部一条设备名（本页触控区不是屏幕最底部，系统手势条由下方的输入区 / 修饰键排
 *   避让，因此设备名放触控区底部即可，不占用手势行程）。
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
        // statusBarsPadding：开关贴在屏幕顶缘。页面根部已做状态栏避让（insets 已被
        // 消费），这里再挂一层是幂等防御——万一根部的避让被调整，按钮仍不会被
        // 状态栏盖住（MIUI 会拦截状态栏区域内的触摸，真机实测过）
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
 * 融合页中部：系统输入法唤起区（Bug 6 核心）。
 *
 * 一个**不可见**的 `BasicTextField`（文字 / 光标 / 手柄全部不可绘）+ 未聚焦时的一行浅色提示。
 * 点击该区域即取得焦点并弹出**手机系统输入法**；用户在系统输入法里打字（含中文、
 * Emoji、符号页），文本变更经 InputConnection 落到本文本框：
 *
 * - `commitText`（含自动改正、候选上屏）→ 文本变长：经 [ImeTextDiff] 差分出追加的
 *   字符，逐字符查 `HidUsageMapper` 转发给被控设备，然后把输入框清回哨兵态；
 * - `deleteSurroundingText` / 退格 → 文本缩短：按缩短的字符数补发退格 usage；
 * - 组词中（`TextFieldValue.composition != null`，中文拼音中间态）→ **只跟手不转发**，
 *   避免把 `n → ni → nihao` 这样的拼音中间态打给对端；候选真正上屏（composition
 *   归 null）后才按上面的规则处理。
 *
 * ## 零宽空格哨兵（[IME_SENTINEL]）
 *
 * 输入框清空后永远保留一个零宽空格（U+200B）：多数输入法在**空输入框**上按退格时
 * 只会做「无可删」处理（不产生任何文本变更），退格就丢了；保留一个不可见字符后，
 * 退格会把哨兵删掉 → 文本缩短 → 正常补发退格 usage，随后再补回哨兵。用户完全
 * 看不到这个字符（零宽 + 文字颜色透明）。
 *
 * ## 弹出 / 收起
 *
 * - **键盘模式自动弹出**（B4）：[keyboardMode] 为 true（五指张开切到 KEYBOARD / NUMPAD，
 *   或 dock 直接选了键盘）时自动取得焦点并唤起系统输入法——「键盘模式」本来就要打字，
 *   不应该还要用户先点一下唤起区；
 * - **触控板模式收起**：切到 TRACKPAD（五指收缩）时 `clearFocus()` + `hide()`，
 *   并把空间让给触控板区（见 [fusedTrackpadWeight]）；
 * - **五指挥势联动收起（问题 3a / 3b，保留）**：系统键盘弹出后屏幕下半是 IME 窗口，落在
 *   它上面的手指到不了 App 的五指挥势层，5 指永远凑不齐。因此本区订阅
 *   [LocalFiveFingerGate]，一出现五指挥势意图就立刻 `clearFocus()` + `hide()`
 *   让出整块屏幕；同时 `clickable` 的门控保证「5 指按下时绝不聚焦弹键盘」。
 *   协调顺序：**意图确立先收，判定结果（张开 / 收缩）出来再决定是否弹出**——
 *   所以收起条件（`shouldYieldToFiveFinger`）优先于自动弹出条件（`keyboardMode`）。
 *
 * ## 光标与手柄为什么整框不可绘（问题 4）
 *
 * `cursorBrush` 透明只藏得住**光标线**；真机（MIUI）上还会留下白色的水滴形
 * 光标手柄 / 选区手柄，而 Compose 的 `BasicTextField` 没有「关闭手柄」的开关。
 * 因此整个输入框挂 `Modifier.graphicsLayer(alpha = 0f)`：**只影响绘制，不影响
 * 输入**——InputConnection、焦点、触摸都照常工作，唯独画不出任何东西。
 * （`graphicsLayer` 不参与命中测试，alpha = 0 的节点依然收到指针事件。）
 *
 * @param onCommitChar 提交了一个字符（调用方负责 fn 组合、修饰键叠加与 HID 映射）
 * @param onBackspace 文本缩短了 N 个字符（调用方补发 N 次退格）
 * @param keyboardMode 当前是否键盘模式（KEYBOARD / NUMPAD）：决定是否自动弹出系统输入法
 */
@Composable
private fun FusedImeInputArea(
    onCommitChar: (Char) -> Unit,
    onBackspace: (Int) -> Unit,
    keyboardMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val contentDesc = stringResource(R.string.cd_fused_ime_input)
    val hint = stringResource(R.string.fused_ime_hint)
    // 五指挥意图闸门：出现五指意图就收起系统输入法，把屏幕让给手势层
    val fiveFingerGate = LocalFiveFingerGate.current
    val yieldToFiveFinger = fiveFingerGate.shouldYieldToFiveFinger
    LaunchedEffect(yieldToFiveFinger, keyboardMode) {
        when {
            // ① 意图确立先收：5 指手势期间绝不让输入法占着屏幕下半（否则手指到不了手势层）
            yieldToFiveFinger -> {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
            // ② 键盘模式自动弹：判定结果是张开（或用户直接选了键盘模式）就弹出
            keyboardMode -> {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
            // ③ 触控板模式收起：判定结果是收缩，或者用户直接切到了触控板模式
            else -> {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }
    }

    // 输入框文本：恒定携带零宽空格哨兵（见 [IME_SENTINEL] 说明）
    var value by remember {
        mutableStateOf(TextFieldValue(IME_SENTINEL, TextRange(IME_SENTINEL.length)))
    }
    // 上一次「组词结束」时已处理的文本：组词期间不更新它，组词结束后一次性差分，
    // 这样拼音中间态既不会被打给对端，也不会被误判成「删除」
    var lastCommitted by remember { mutableStateOf(IME_SENTINEL) }
    var focused by remember { mutableStateOf(false) }
    // 批量上屏的节拍发送队列（串行化，见 onValueChange 里的说明）
    val scope = rememberCoroutineScope()
    var sendJob by remember { mutableStateOf<Job?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        FusedSectionSeam()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(PureBlack)
                    // 点区域任意位置（含提示文字）都能聚焦并唤起系统输入法。
                    // 五指挥势进行中一律不聚焦、不弹键盘（问题 3b：让位给模式切换手势）
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) {
                        if (!fiveFingerGate.shouldYieldToFiveFinger) {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // 问题 4：把「选区 / 光标手柄」的颜色也置透明。
                //
                // `cursorBrush` 只藏得住光标**线**；Compose 的水滴形光标手柄 / 选区手柄
                // 走的是 `LocalTextSelectionColors.current.handleColor`，而且是在
                // **Popup 窗口**里画的（`HandlePopup`，见 AndroidCursorHandle.android.kt），
                // 因此挂在本输入框上的 `graphicsLayer(alpha = 0f)` 盖不住它——Popup 是
                // 独立窗口，不是输入框 RenderNode 的子节点。
                //
                // 但 Popup 的内容是在**声明处**的组合作用域里组合的，CompositionLocal
                // 照样会透传进去，所以在这里包一层透明 `LocalTextSelectionColors` 就能
                // 让手柄画出来也是全透明。选区背景一并置透明，避免出现选区色块。
                CompositionLocalProvider(
                    LocalTextSelectionColors provides TextSelectionColors(
                        handleColor = Color.Transparent,
                        backgroundColor = Color.Transparent,
                    ),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { next ->
                            if (next.composition != null) {
                                // 组词中（中文拼音等）：只跟手更新文本，不转发、不清框
                                value = next
                                return@BasicTextField
                            }
                            val actions = ImeTextDiff.diff(lastCommitted, next.text)
                            // 转发后统一清回哨兵态：对端已收到这些字符，本框不再保留
                            lastCommitted = IME_SENTINEL
                            value = TextFieldValue(
                                text = IME_SENTINEL,
                                selection = TextRange(IME_SENTINEL.length),
                            )
                            val dispatch: (ImeKeyAction) -> Unit = { action ->
                                when (action) {
                                    is ImeKeyAction.Backspace -> onBackspace(action.count)
                                    is ImeKeyAction.Commit -> onCommitChar(action.char)
                                }
                            }
                            if (actions.size <= 1) {
                                // 单字符（真人打字节奏）：同步直发，零额外延迟
                                actions.forEach(dispatch)
                            } else {
                                // 批量上屏（输入法整词提交 / 粘贴）：按节拍排队发送。
                                // 实测 macOS HID 对毫秒级连发会丢键（6 键 / 160ms 只到 3 键），
                                // 逐键间隔 KEY_BURST_SPACING_MS 后全部送达；串行化保证
                                // 两次批量上屏不交错
                                sendJob = scope.launch {
                                    sendJob?.join()
                                    actions.forEach { action ->
                                        dispatch(action)
                                        delay(KEY_BURST_SPACING_MS)
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            // 双保险：整框不可绘。alpha = 0 的 graphicsLayer 只影响绘制，
                            // 输入 / 焦点 / 触摸全部照常（graphicsLayer 不参与命中测试）
                            .graphicsLayer(alpha = 0f)
                            .fillMaxSize()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focused = it.isFocused }
                            .semantics { this.contentDescription = contentDesc },
                        // 双保险：即便整框不可绘，文字与光标也仍然是透明的
                        textStyle = TextStyle(color = Color.Transparent),
                        cursorBrush = SolidColor(Color.Transparent),
                        // 多行：回车键走「插入 \n」而不是 IME action，\n 由 HidUsageMapper
                        // 映射成 KEY_ENTER 发给对端
                        singleLine = false,
                        keyboardOptions = KeyboardOptions(),
                    )
                }


            // 未聚焦时的浅色提示（聚焦后隐藏，让位给系统输入法）
            if (!focused) {
                Text(
                    text = hint,
                    color = PureWhite.copy(alpha = 0.32f),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }

    // 离开页面时收起系统输入法并清除焦点，避免输入法盖住下一个页面
    DisposableEffect(Unit) {
        onDispose {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }
}

/** 零宽空格哨兵：输入框里恒定保留的不可见字符（退格捕获，见 [FusedImeInputArea] 说明）。 */
private const val IME_SENTINEL = "\u200B"

/** 批量上屏的键间隔（ms）：macOS HID 对毫秒级连发会丢键，逐键留出解析余量。 */
private const val KEY_BURST_SPACING_MS = 12L

/** 融合页顶部分隔线：居中淡出的 1dp 细线（触控板 / 输入区 / 修饰键排三段之分界）。 */
@Composable
private fun FusedSectionSeam(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind { drawCenteredFadingSeamLine(size.width, size.height) },
    )
}

/**
 * 融合页底部：一排**可锁定修饰键**（Bug 6）。
 *
 * - ctrl / shift / fn / win(⌘) / alt(option)：点击进入锁定态（整颗键帽高亮 + 触觉），
 *   再点解锁；锁定期间经系统输入法提交的每个字符都带上对应修饰键位图
 *   （锁定 ctrl 后打 c = Ctrl+C；锁定 shift 后打 a = A）；
 * - fn：锁定视觉与其它修饰键一致，组合语义沿用横屏 87 键键盘页——锁定的 fn 被第一个
 *   fn 组合（空格 / C / S / V）消费后即解锁（见 [FnComboActions]）；
 * - tab / esc：不锁定，点击直接发送 usage（锁定的修饰键照常叠加，如 Ctrl+Tab）。
 *
 * 键帽复用键盘页的 [KeyCap]：渐变分隔细线（需求 5）、按压下陷 + 触觉、以及
 * `pocketKeyGestures` 的「≥5 指针放行五指挥势层」仲裁，与横屏 87 键同一套交互。
 */
@Composable
private fun FusedModifierRow(
    platform: DevicePlatform,
    locked: Set<LockableModifier>,
    onToggleModifier: (LockableModifier) -> Unit,
    onDirectAction: (KeyAction) -> Unit,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val vibrator = rememberFusedVibrator()
    val specs = remember(platform) { fusedModifierRow(platform) }

    Column(modifier = modifier.fillMaxWidth()) {
        FusedSectionSeam()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(PureBlack),
        ) {
            specs.forEach { spec ->
                val active = spec.modifier != null && spec.modifier in locked
                KeyCap(
                    spec = spec.spec,
                    fnActive = false,
                    highlighted = active,
                    showSeams = true,
                    textColor = textColor,
                    // 修饰键排是紧凑的固定高度 UI，字号不随 fn+S 偏好变化
                    textSize = KeyTextSize.SMALL,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onPress = {
                        // 走 onPress 而不是 onRelease：`pocketKeyGestures` 的「五指防误触
                        // 门控」挂在激活那一刻（onPress）上，5 指凑齐时整颗键作废、
                        // onPress 根本不会被调用——修饰键因此不会在五指挥势中被误锁定。
                        // 放在 onRelease 上则会绕过门控：作废时 onRelease 照旧触发。
                        if (spec.modifier != null) {
                            onToggleModifier(spec.modifier)
                        } else {
                            onDirectAction(spec.spec.action)
                        }
                    },
                    // 报告在 onPress 那一刻就发了，正常抬起无需再做事
                    onRelease = {},
                    onAbandoned = {},
                )
            }
        }
    }
}

/**
 * 修饰键排上的一颗键。
 *
 * @param spec 键帽描述（文案 / 语义 / 风格）
 * @param modifier 非空 = 可锁定修饰键（点击切换锁定态）；null = 直接发送键（tab / esc）
 */
internal data class FusedModifierSpec(
    val spec: KeySpec,
    val modifier: LockableModifier?,
)

/**
 * 修饰键排的 7 颗键（随控制设备平台切换标签：Windows = ctrl/win/alt，
 * 苹果 = control/option/⌘；shift / fn / tab / esc 两平台一致）。
 *
 * 顺序与需求一致：ctrl、shift、fn、win-option、alt-cmd、tab、esc。
 */
internal fun fusedModifierRow(platform: DevicePlatform): List<FusedModifierSpec> = when (platform) {
    DevicePlatform.APPLE -> listOf(
        FusedModifierSpec(
            spec = KeySpec(
                labelRes = R.string.fused_mod_control,
                action = KeyAction.Modifier(HidUsage.KEY_LEFT_CONTROL),
                style = KeyStyle.MODIFIER,
                contentDescRes = R.string.fused_mod_control,
            ),
            modifier = LockableModifier.CTRL,
        ),
        FusedModifierSpec(
            spec = KeySpec(
                labelRes = R.string.fused_mod_shift,
                action = KeyAction.Modifier(HidUsage.KEY_LEFT_SHIFT),
                style = KeyStyle.MODIFIER,
                contentDescRes = R.string.fused_mod_shift,
            ),
            modifier = LockableModifier.SHIFT,
        ),
        FusedModifierSpec(
            spec = KeySpec(
                labelRes = R.string.fused_mod_fn,
                action = KeyAction.Fn,
                style = KeyStyle.FN,
                contentDescRes = R.string.fused_mod_fn,
            ),
            modifier = LockableModifier.FN,
        ),
        FusedModifierSpec(
            spec = KeySpec(
                labelRes = R.string.fused_mod_option,
                action = KeyAction.Modifier(HidUsage.KEY_LEFT_ALT),
                style = KeyStyle.MODIFIER,
                contentDescRes = R.string.fused_mod_option,
            ),
            modifier = LockableModifier.ALT,
        ),
        FusedModifierSpec(
            spec = KeySpec(
                labelRes = R.string.fused_mod_cmd,
                action = KeyAction.Modifier(HidUsage.KEY_LEFT_GUI),
                style = KeyStyle.MODIFIER,
                contentDescRes = R.string.fused_mod_cmd,
            ),
            modifier = LockableModifier.GUI,
        ),
        tabKey(),
        escKey(),
    )

    DevicePlatform.OTHER -> listOf(
        FusedModifierSpec(
            spec = KeySpec(
                labelRes = R.string.fused_mod_ctrl,
                action = KeyAction.Modifier(HidUsage.KEY_LEFT_CONTROL),
                style = KeyStyle.MODIFIER,
                contentDescRes = R.string.fused_mod_ctrl,
            ),
            modifier = LockableModifier.CTRL,
        ),
        FusedModifierSpec(
            spec = KeySpec(
                labelRes = R.string.fused_mod_shift,
                action = KeyAction.Modifier(HidUsage.KEY_LEFT_SHIFT),
                style = KeyStyle.MODIFIER,
                contentDescRes = R.string.fused_mod_shift,
            ),
            modifier = LockableModifier.SHIFT,
        ),
        FusedModifierSpec(
            spec = KeySpec(
                labelRes = R.string.fused_mod_fn,
                action = KeyAction.Fn,
                style = KeyStyle.FN,
                contentDescRes = R.string.fused_mod_fn,
            ),
            modifier = LockableModifier.FN,
        ),
        FusedModifierSpec(
            spec = KeySpec(
                labelRes = R.string.fused_mod_win,
                action = KeyAction.Modifier(HidUsage.KEY_LEFT_GUI),
                style = KeyStyle.MODIFIER,
                contentDescRes = R.string.fused_mod_win,
            ),
            modifier = LockableModifier.GUI,
        ),
        FusedModifierSpec(
            spec = KeySpec(
                labelRes = R.string.fused_mod_alt,
                action = KeyAction.Modifier(HidUsage.KEY_LEFT_ALT),
                style = KeyStyle.MODIFIER,
                contentDescRes = R.string.fused_mod_alt,
            ),
            modifier = LockableModifier.ALT,
        ),
        tabKey(),
        escKey(),
    )
}

/** tab 键：点击直接发送（不锁定）。 */
private fun tabKey(): FusedModifierSpec = FusedModifierSpec(
    spec = KeySpec(
        labelRes = R.string.fused_mod_tab,
        action = KeyAction.Usage(HidUsage.KEY_TAB),
        style = KeyStyle.MODIFIER,
        contentDescRes = R.string.fused_mod_tab,
    ),
    modifier = null,
)

/** esc 键：点击直接发送（不锁定）。 */
private fun escKey(): FusedModifierSpec = FusedModifierSpec(
    spec = KeySpec(
        labelRes = R.string.fused_mod_esc,
        action = KeyAction.Usage(HidUsage.KEY_ESCAPE),
        style = KeyStyle.MODIFIER,
        contentDescRes = R.string.fused_mod_esc,
    ),
    modifier = null,
)

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
