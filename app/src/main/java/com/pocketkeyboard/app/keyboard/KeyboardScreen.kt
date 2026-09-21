package com.pocketkeyboard.app.keyboard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketkeyboard.app.R
import com.pocketkeyboard.app.hid.HidController
import com.pocketkeyboard.app.hid.HidModifier
import com.pocketkeyboard.app.hid.HidTransport
import com.pocketkeyboard.app.hid.HidUsage
import com.pocketkeyboard.app.hid.HidUsageMapper
import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.theme.PureBlack
import com.pocketkeyboard.app.ui.theme.PureWhite
import kotlinx.coroutines.launch

/**
 * 引导键盘报告最多同时按下的普通按键数。
 */
private const val MAX_HELD_KEYS = 6

/**
 * 键盘页（87 键 TKL）。
 *
 * 职责划分：
 * - 键位与宽度全部由 [TklLayout] 的 u 比例数据模型驱动，按
 *   [MainViewModel.activeDevice] 的 `platform` 在苹果 / Windows 两套布局间切换；
 * - 按键事件经 [HidTransport] 契约接口下发：字符 / 功能键走 `sendKeyboardReport`
 *   （含修饰键位掩码，支持和弦），F 行默认行为走 `sendConsumerUsage`；
 * - fn 组合（空格 / C / S / V）只改本机状态（背光 HUD、键帽颜色、字号、震感），
 *   **不发送给被控设备**；
 * - 每个键帽挂 `Modifier.pocketKeyGestures`，≥5 指针时不消费、放行给底层五指挥势层，
 *   因此键盘层不消费五指挥势（见 gesture/KeyPointerInput.kt 的传递策略说明）。
 *
 * @param transport HID 传输实现。null 时本页自建 [HidController] 并随组合生命周期启停；
 *    若 MainActivity / 配对页后续统一持有 HidController，把它传进来即可避免重复注册。
 */
@Composable
fun KeyboardScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel(),
    transport: HidTransport? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val activeDevice by viewModel.activeDevice.collectAsStateWithLifecycle()
    val platform = activeDevice?.platform ?: DevicePlatform.OTHER

    // 传输：调用方未提供时自建（启动 / 停止随本页组合生命周期）
    val effectiveTransport = transport ?: rememberKeyboardTransport(viewModel)
    val engine = remember(effectiveTransport) { KeyboardReportEngine(effectiveTransport) }

    // 页面退出（切到触控板 / 配对页）时把所有按住的键松开：
    // 否则按住一个键切页后 heldUsages 不会清，对端会一直卡在那个键上
    DisposableEffect(engine) {
        onDispose { engine.releaseAll() }
    }

    // 偏好：键帽颜色 / 字号 / 震感，全部持久化到 DataStore
    val preferencesStore = remember { KeyboardPreferencesStore(context) }
    val preferences by preferencesStore.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())

    // 震感档位与触控板反馈强度同步（单例共享）
    LaunchedEffect(preferences.hapticStrength) {
        HapticScale.set(preferences.hapticStrength)
    }

    // fn 状态：fnHeld = 按住；fnLatched = 轻点后的粘滞（单次组合用完即清除）
    var fnHeld by remember { mutableStateOf(false) }
    var fnLatched by remember { mutableStateOf(false) }
    var fnComboUsed by remember { mutableStateOf(false) }
    val fnActive = fnHeld || fnLatched

    // F 行本次按下走的是哪条路径：true = Keyboard Page 本义（fn 活跃），
    // false = Consumer Page 媒体键（fn 未活跃）。松手必须按**同一条**路径释放：
    // 「先 fn+F1 再单独按 F1」时，若松手无条件发 consumer(0)，刚刚按下的媒体键
    // 会被自己的松键立刻清掉（usage = 0 对 Consumer 是显式释放，不是空报告）。
    val functionKeyPath = remember { mutableStateMapOf<KeySpec, Boolean>() }

    // fn + 空格的本机背光档位与 HUD
    var brightnessLevel by remember { mutableIntStateOf(0) }
    var hud by remember { mutableStateOf<KeyboardHudRequest?>(null) }

    // HUD 文案全部来自 strings.xml（组合期解析；按键回调里没有 Composable 上下文）
    val brightnessLabels = listOf(
        stringResource(R.string.hud_brightness_100),
        stringResource(R.string.hud_brightness_75),
        stringResource(R.string.hud_brightness_50),
        stringResource(R.string.hud_brightness_25),
        stringResource(R.string.hud_brightness_min),
    )
    val keyColorLabels = listOf(
        stringResource(R.string.hud_key_color_white),
        stringResource(R.string.hud_key_color_orange),
        stringResource(R.string.hud_key_color_red),
    )
    val textSizeLabels = listOf(
        stringResource(R.string.hud_text_size_small),
        stringResource(R.string.hud_text_size_medium),
        stringResource(R.string.hud_text_size_large),
    )
    val hapticLabels = listOf(
        stringResource(R.string.hud_haptic_off),
        stringResource(R.string.hud_haptic_weak),
        stringResource(R.string.hud_haptic_medium),
        stringResource(R.string.hud_haptic_strong),
    )
    val title = stringResource(R.string.keyboard_title)
    val fnHint = stringResource(R.string.keyboard_fn_hint)

    val vibrator = rememberVibrator()
    val keyCapColor = preferences.keyCapColor.toKeyCapTextColor()

    // ---------------------------------------------------------------- fn 组合（本机功能，不发给被控设备）

    fun cycleBrightness() {
        brightnessLevel = (brightnessLevel + 1) % BRIGHTNESS_LEVELS.size
        applyWindowBrightness(context, BRIGHTNESS_LEVELS[brightnessLevel])
        hud = KeyboardHudRequest.Brightness(
            BrightnessHudRequest(level = brightnessLevel, label = brightnessLabels[brightnessLevel]),
        )
    }

    fun cycleKeyCapColor() {
        val values = KeyCapColor.entries
        val next = values[(preferences.keyCapColor.ordinal + 1) % values.size]
        scope.launch { preferencesStore.setKeyCapColor(next) }
        hud = KeyboardHudRequest.Message(MessageHudRequest(keyColorLabels[next.ordinal]))
    }

    fun cycleTextSize() {
        val values = KeyTextSize.entries
        val next = values[(preferences.keyTextSize.ordinal + 1) % values.size]
        scope.launch { preferencesStore.setKeyTextSize(next) }
        hud = KeyboardHudRequest.Message(MessageHudRequest(textSizeLabels[next.ordinal]))
    }

    fun cycleHapticStrength() {
        val values = HapticStrength.entries
        val next = values[(preferences.hapticStrength.ordinal + 1) % values.size]
        scope.launch { preferencesStore.setHapticStrength(next) }
        hud = KeyboardHudRequest.Message(MessageHudRequest(hapticLabels[next.ordinal]))
    }

    // ---------------------------------------------------------------- 按键 → HID 报告

    fun onKeyDown(spec: KeySpec) {
        // fn / Globe：只切本机 fn 状态，不发送给被控设备
        if (spec.action == KeyAction.Fn) {
            fnHeld = true
            fnComboUsed = false
            performKeyHaptic(view, vibrator)
            return
        }

        if (fnActive) {
            fnComboUsed = true
            // 粘滞的 fn 只生效一次组合：命中组合键即刻清除，否则「轻点 fn（粘滞亮起）
            // → 按 C 换颜色 → 再按 C」会重复触发本机功能，与「单次组合」的约定矛盾。
            // 四个 fn 本机功能分支都会提前 return，所以清除必须放在它们之前。
            fnLatched = false
            val action = spec.action
            // fn + 空格 / C / S / V：本机功能，不发送给被控设备
            if (action is KeyAction.Usage && action.usage == HidUsage.KEY_SPACE) {
                performKeyHaptic(view, vibrator)
                cycleBrightness()
                return
            }
            if (action is KeyAction.Character) {
                when (action.char.lowercaseChar()) {
                    'c' -> {
                        performKeyHaptic(view, vibrator)
                        cycleKeyCapColor()
                        return
                    }

                    's' -> {
                        performKeyHaptic(view, vibrator)
                        cycleTextSize()
                        return
                    }

                    'v' -> {
                        performKeyHaptic(view, vibrator)
                        cycleHapticStrength()
                        return
                    }

                    else -> Unit
                }
            }
            // fn + 其他键：粘滞状态在上面已清除，这里继续正常发送报告
        }

        performKeyHaptic(view, vibrator)
        when (val action = spec.action) {
            is KeyAction.Character -> {
                val mapping = HidUsageMapper.usageForChar(action.char)
                if (mapping != null) engine.keyDown(mapping.usage, mapping.needsShift)
            }

            is KeyAction.Usage -> engine.keyDown(action.usage)

            is KeyAction.Modifier -> engine.modifierDown(action.usage)

            is KeyAction.FunctionKey ->
                // fn 活跃：F 行发送 F1–F12 本义；否则发送 Consumer 媒体键。
                // 走哪条路径记进 functionKeyPath，松手时按同一路径释放。
                if (fnActive) {
                    functionKeyPath[spec] = true
                    engine.keyDown(action.nativeUsage)
                } else {
                    functionKeyPath[spec] = false
                    engine.consumer(action.consumerUsage)
                }

            KeyAction.Fn -> Unit
        }
    }

    fun onKeyUp(spec: KeySpec) {
        when (val action = spec.action) {
            KeyAction.Fn -> {
                fnHeld = false
                // 本轮按住期间用过组合键 → 清除粘滞；否则视为「轻点 fn」切换粘滞
                if (fnComboUsed) {
                    fnLatched = false
                } else {
                    fnLatched = !fnLatched
                }
                fnComboUsed = false
            }

            is KeyAction.Character ->
                HidUsageMapper.usageForChar(action.char)?.let { engine.keyUp(it.usage) }

            is KeyAction.Usage -> engine.keyUp(action.usage)

            is KeyAction.Modifier -> engine.modifierUp(action.usage)

            is KeyAction.FunctionKey -> {
                // 松手按按下时记录的同一路径释放；记录丢失（例如按下记录未来得及写入）
                // 时两条路径都释放，宁可发空报告也不能卡键
                when (functionKeyPath.remove(spec)) {
                    true -> engine.keyUp(action.nativeUsage)
                    false -> engine.consumer(0)
                    null -> {
                        engine.keyUp(action.nativeUsage)
                        engine.consumer(0)
                    }
                }
            }
        }
    }

    /** 五指挥势作废本次按下（≥5 指针）：必须补发松键，避免卡键 / 残留 fn 状态。 */
    fun onKeyAbandoned(spec: KeySpec) {
        if (spec.action == KeyAction.Fn) {
            fnHeld = false
            fnLatched = false
            fnComboUsed = false
        } else {
            onKeyUp(spec)
        }
    }

    // ---------------------------------------------------------------- 渲染

    val rows = remember(platform) { TklLayout.rows(platform) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 6.dp),
        ) {
            KeyboardHeader(
                title = title,
                fnHint = fnHint,
                fnActive = fnActive,
            )
            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    row.items.forEach { item ->
                        when (item) {
                            is RowItem.Key -> KeyCap(
                                spec = item.spec,
                                fnActive = fnActive,
                                textColor = keyCapColor,
                                textSize = preferences.keyTextSize,
                                onPress = { onKeyDown(item.spec) },
                                onRelease = { onKeyUp(item.spec) },
                                onAbandoned = { onKeyAbandoned(item.spec) },
                                modifier = Modifier
                                    .weight(item.widthU)
                                    .fillMaxHeight(),
                            )

                            is RowItem.Stacked -> Column(
                                modifier = Modifier
                                    .weight(item.widthU)
                                    .fillMaxHeight(),
                            ) {
                                item.keys.forEach { spec ->
                                    KeyCap(
                                        spec = spec,
                                        fnActive = fnActive,
                                        textColor = keyCapColor,
                                        textSize = preferences.keyTextSize,
                                        onPress = { onKeyDown(spec) },
                                        onRelease = { onKeyUp(spec) },
                                        onAbandoned = { onKeyAbandoned(spec) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // HUD：屏幕中央、iOS 风格；不加 pointerInput，对触摸完全透明
        KeyboardHudLayer(
            hud = hud,
            onDismiss = { hud = null },
        )
    }
}

/**
 * 键盘顶部：fn 活跃时一条白色高亮条（「键盘顶部高亮」）+ 标题 + fn 组合键提示。
 */
@Composable
private fun KeyboardHeader(
    title: String,
    fnHint: String,
    fnActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val barAlpha by animateFloatAsState(
        targetValue = if (fnActive) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "fnTopBarAlpha",
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(PureWhite.copy(alpha = barAlpha)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = PureWhite.copy(alpha = 0.45f),
                fontSize = 11.sp,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = fnHint,
                color = PureWhite.copy(alpha = if (fnActive) 0.75f else 0.3f),
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * 自建 HID 传输：本页没有拿到调用方传入的 [HidTransport] 时，
 * 用 [HidController] 组装系统实现（或不可用时的空实现），并随组合生命周期启停。
 */
@Composable
private fun rememberKeyboardTransport(viewModel: MainViewModel): HidTransport {
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
private fun rememberVibrator(): Vibrator? {
    val context = LocalContext.current
    return remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}

/**
 * 按键触觉反馈，按 [HapticScale] 档位执行：
 * - 关：不反馈；
 * - 中：系统标准 `HapticFeedbackConstants.KEYBOARD_TAP`；
 * - 弱 / 强：Vibrator 单次震动，振幅 70 / 255（时长 8ms / 18ms）。
 */
internal fun performKeyHaptic(
    view: View,
    vibrator: Vibrator?,
    strength: HapticStrength = HapticScale.strength.value,
) {
    when (strength) {
        HapticStrength.OFF -> Unit
        HapticStrength.MEDIUM ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        HapticStrength.WEAK -> vibrateOnce(vibrator, durationMs = 8L, amplitude = 70)
        HapticStrength.STRONG -> vibrateOnce(vibrator, durationMs = 18L, amplitude = 255)
    }
}

private fun vibrateOnce(vibrator: Vibrator?, durationMs: Long, amplitude: Int) {
    if (vibrator == null || !vibrator.hasVibrator()) return
    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
}

/** 把本机窗口亮度设为 [level]（0..1；只影响当前 Activity 窗口，无需 WRITE_SETTINGS）。 */
private fun applyWindowBrightness(context: Context, level: Float) {
    val activity = context.findActivity() ?: return
    val window = activity.window ?: return
    val attributes = window.attributes
    attributes.screenBrightness = level
    window.attributes = attributes
}

/** 沿 ContextWrapper 链找到宿主 Activity（拿不到返回 null）。 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 键盘报告引擎：把「按下 / 松开」翻译成标准 8 字节引导键盘报告。
 *
 * - 修饰键按住期间置位（bit 图），松手清位，因此 cmd + shift + a 这类和弦可以多指同时按；
 * - 普通按键最多同时记录 [MAX_HELD_KEYS] 个，松开即从报告里移除；
 * - 需要 Shift 才能输入的字符（! @ # …）在按下时叠加 LeftShift 位，不需要用户真的按 Shift。
 */
internal class KeyboardReportEngine(
    private val transport: HidTransport,
) {

    private var modifierBits: Int = HidModifier.NONE
    private val heldUsages = ArrayDeque<Int>()

    /** 修饰键按下（usage 见 `HidUsage.KEY_LEFT_GUI` 等）。 */
    fun modifierDown(usage: Int) {
        modifierBits = modifierBits or HidModifier.bitFor(usage)
        flush()
    }

    /** 修饰键松开。 */
    fun modifierUp(usage: Int) {
        modifierBits = modifierBits and HidModifier.bitFor(usage).inv()
        flush()
    }

    /** 普通按键按下；[momentaryShift] 为 true 时叠加 LeftShift 位。 */
    fun keyDown(usage: Int, momentaryShift: Boolean = false) {
        val bits = if (momentaryShift) modifierBits or HidModifier.LEFT_SHIFT else modifierBits
        if (heldUsages.size < MAX_HELD_KEYS && usage !in heldUsages) {
            heldUsages.addLast(usage)
        }
        transport.sendKeyboardReport(bits, heldUsages.toReportBytes())
    }

    /** 普通按键松开。 */
    fun keyUp(usage: Int) {
        heldUsages.remove(usage)
        flush()
    }

    /** Consumer Page (0x0C) 媒体键；usage = 0 表示松开。 */
    fun consumer(usage: Int) {
        transport.sendConsumerUsage(usage)
    }

    /** 全部松开（页面退出 / 异常兜底）。 */
    fun releaseAll() {
        modifierBits = HidModifier.NONE
        heldUsages.clear()
        flush()
    }

    private fun flush() {
        transport.sendKeyboardReport(modifierBits, heldUsages.toReportBytes())
    }

    private fun ArrayDeque<Int>.toReportBytes(): ByteArray =
        map { it.toByte() }.toByteArray()
}
