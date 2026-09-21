package com.pocketkeyboard.app.trackpad

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketkeyboard.app.R
import com.pocketkeyboard.app.gesture.GestureArbiter
import com.pocketkeyboard.app.hid.HidController
import com.pocketkeyboard.app.hid.HidTransport
import com.pocketkeyboard.app.hid.MouseButton
import com.pocketkeyboard.app.keyboard.HapticScale
import com.pocketkeyboard.app.keyboard.KeyboardPreferences
import com.pocketkeyboard.app.keyboard.KeyboardPreferencesStore
import com.pocketkeyboard.app.keyboard.performKeyHaptic
import com.pocketkeyboard.app.keyboard.toKeyCapTextColor
import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.PairedDevice
import com.pocketkeyboard.app.ui.theme.PureBlack
import com.pocketkeyboard.app.ui.theme.PureWhite
import kotlin.math.roundToInt

/**
 * 触控板页。
 *
 * ## 页面结构
 *
 * ```
 * ┌───────────────────────────────────────┐
 * │                             [⌗ 123]  │  ← 右上角小键盘开关（极简）
 * │                                       │
 * │            触控区（全屏纯黑）           │  ← 1–4 指手势 + 五指挥势仲裁
 * │                                       │
 * │                   │                   │  ← 仅 Windows 模式：底带中央一条短竖线，
 * │         当前控制设备名（28dp 安全区）    │     把底带分成左右两个点击区
 * └───────────────────────────────────────┘
 * ```
 *
 * 底部的左右点击区**不画边框、不印文字**，静态时与纯黑背景融为一体，只有中央那条
 * [TrackpadDivider] 短竖线提示「这里可以点左右键」：点左半 = 左键、点右半 = 右键，
 * 从左半 / 右半按住拖动 = 对应键拖拽。Apple 模式（单指 = 左键、双指 = 右键）不显示点击区。
 *
 * ## 与其它模块的关系
 * - 手势经 [GestureArbiter] 与页面容器最底层的 `Modifier.pocketGestures` 仲裁：
 *   60ms 内凑满 5 指归五指挥势层（收缩 / 张开切模式、整体横滑切设备），否则归本页的
 *   1–4 指手势，详见 [TrackpadPointerInput] 里的详细注释；
 * - 所有 HID 输出严格走 [HidTransport] 契约接口，见 [TrackpadHidActions]；
 * - 小键盘从底部以苹果式 sheet 滑入（[NumpadSheet]），不切换 `MainViewModel.mode`，
 *   因此不会触发 MainActivity 的页面转场。
 *
 * @param transport HID 传输实现。null 时本页自建 `HidController` 并随组合生命周期启停；
 *    若调用方统一持有 HidController，把它传进来即可避免重复注册。
 */
@Composable
fun TrackpadScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel(),
    arbiter: GestureArbiter = remember { GestureArbiter() },
    transport: HidTransport? = null,
) {
    val activeDevice by viewModel.activeDevice.collectAsStateWithLifecycle()
    val platform = activeDevice?.platform ?: DevicePlatform.OTHER

    // 键盘页偏好（颜色 / 字号 / 震感）：小键盘复用键盘页 KeyCap，偏好链路必须接通，
    // 否则 fn+C / fn+S 调过的键帽颜色与字号对小键盘不生效
    val context = LocalContext.current
    val preferencesStore = remember { KeyboardPreferencesStore(context) }
    val preferences by preferencesStore.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())

    // 震感档位与键盘页同步（单例）：本页可能是用户进入 App 后的第一页，
    // 不等键盘页组合就要把持久化档位推给 HapticScale，小键盘 / 触控板反馈才对
    LaunchedEffect(preferences.hapticStrength) {
        HapticScale.set(preferences.hapticStrength)
    }

    // 传输：调用方未提供时自建（启动 / 停止随本页组合生命周期）
    val effectiveTransport = transport ?: rememberTrackpadTransport(viewModel)
    val actions = remember(effectiveTransport) { TrackpadHidActions(effectiveTransport) }
    val handler = remember(actions) { TrackpadGestureHandler { gesture -> actions.dispatch(gesture) } }

    // px 级阈值：触摸 slop 与三指挥手距离
    val thresholds = with(LocalDensity.current) {
        TrackpadThresholds(
            tapSlopPx = TrackpadMetrics.TAP_SLOP.toPx(),
            swipeDistancePx = TrackpadMetrics.SWIPE_DISTANCE.toPx(),
        )
    }

    // 小键盘 sheet 开关：只在用户显式操作（点右上角开关 / 点遮罩 / 按返回键）时变化。
    // 控制设备变化（五指横滑、对端重连）不被动收起——那会打断连续的数字录入，
    // 而 sheet 本来就是用户主动打开的覆盖层。
    var numpadVisible by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TrackpadSurface(
            platform = platform,
            handler = handler,
            arbiter = arbiter,
            thresholds = thresholds,
            transport = effectiveTransport,
            modifier = Modifier.fillMaxSize(),
        )

        // 右上角：小键盘开关
        NumpadToggleButton(
            onClick = { numpadVisible = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        )

        // 底部 28dp 安全区：当前控制设备名
        DeviceNameStrip(
            device = activeDevice,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

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

/**
 * 触控区：全屏纯黑，承载 1–4 指手势；Windows 模式下底部叠一条点击带
 * （左半 / 右半两个点击区 + 中央一条短竖线）。
 *
 * 两个点击区是触控区的**子节点**（因此它们的手势也在触控区的 hit 路径上），
 * 但它们的 down 会被自己 consume 掉 —— 触控区的 [awaitTrackpadDown] 专门放行
 * 「落在点击区里的已消费按下」，否则「按住左半 + 滑动 = 左键拖拽」就看不到了。
 */
@Composable
private fun TrackpadSurface(
    platform: DevicePlatform,
    handler: TrackpadGestureHandler,
    arbiter: GestureArbiter,
    thresholds: TrackpadThresholds,
    transport: HidTransport,
    modifier: Modifier = Modifier,
) {
    val clickZonesEnabled = platform == DevicePlatform.OTHER

    BoxWithConstraints(
        modifier = modifier
            .background(PureBlack)
            .trackpadGestures(
                platform = platform,
                handler = handler,
                arbiter = arbiter,
                thresholds = thresholds,
                clickZonesEnabled = clickZonesEnabled,
            ),
    ) {
        if (clickZonesEnabled) {
            // 用与手势层完全相同的函数算出点击带矩形，保证「画出来的」和「判定的」是同一块区域
            val density = LocalDensity.current
            val zones = remember(maxWidth, maxHeight, density) {
                clickZones(
                    widthPx = with(density) { maxWidth.toPx() },
                    heightPx = with(density) { maxHeight.toPx() },
                    density = density,
                )
            }

            // 左半：轻点 = 左键，按住拖动 = 左键拖拽
            TrackpadClickZone(
                modifier = Modifier
                    .offset { IntOffset(zones.left.left.roundToInt(), zones.left.top.roundToInt()) }
                    .size(
                        with(density) { zones.left.width.toDp() },
                        with(density) { zones.left.height.toDp() },
                    ),
                onPress = { transport.sendMouseButton(MouseButton.LEFT, true) },
                onRelease = { transport.sendMouseButton(MouseButton.LEFT, false) },
            )

            // 右半：轻点 = 右键，按住拖动 = 右键拖拽
            TrackpadClickZone(
                modifier = Modifier
                    .offset { IntOffset(zones.right.left.roundToInt(), zones.right.top.roundToInt()) }
                    .size(
                        with(density) { zones.right.width.toDp() },
                        with(density) { zones.right.height.toDp() },
                    ),
                onPress = { transport.sendMouseButton(MouseButton.RIGHT, true) },
                onRelease = { transport.sendMouseButton(MouseButton.RIGHT, false) },
            )

            // 点击带正中央一条短竖线：左右点击区的视觉分界（自身不拦截触摸，对触摸完全透明）。
            // 用一个与点击带同位置同尺寸的 Box 当锚点，分割线靠 Alignment.Center 自动居中
            Box(
                modifier = Modifier
                    .offset { IntOffset(zones.band.left.roundToInt(), zones.band.top.roundToInt()) }
                    .size(
                        with(density) { zones.band.width.toDp() },
                        with(density) { zones.band.height.toDp() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                TrackpadDivider()
            }
        }
    }
}

/** 底部 28dp 安全区：显示当前控制设备名（无设备时显示未连接提示）。 */
@Composable
private fun DeviceNameStrip(
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

    // 这一层不加 pointerInput：对触摸完全透明，落在安全区里的手指仍由触控区处理
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
 * 右上角小键盘开关：一个极简的点阵小图标 + 「123」字样。
 *
 * 点击后从底部滑入 [NumpadSheet]；收起由 sheet 顶部自己的「完成」按钮、
 * 遮罩点击或系统返回键完成（关闭是单向动作，不复用这个「开关」语义的按钮）。
 */
@Composable
internal fun NumpadToggleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val vibrator = rememberTrackpadVibrator()
    val label = stringResource(R.string.trackpad_numpd_toggle)
    val description = stringResource(R.string.cd_numpad_toggle)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable {
                performKeyHaptic(view, vibrator)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumpadGlyph(
            color = PureWhite.copy(alpha = 0.55f),
            modifier = Modifier.size(15.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = PureWhite.copy(alpha = 0.55f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 数字小键盘点阵图标：3 × 3 小白点（与「123」文案一起表示小键盘）。 */
@Composable
private fun NumpadGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val cell = minOf(size.width, size.height) / 3f
        val radius = cell * 0.26f
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                drawCircle(
                    color = color,
                    radius = radius,
                    center = Offset((column + 0.5f) * cell, (row + 0.5f) * cell),
                )
            }
        }
    }
}

/** 触控板页自建 HID 传输：调用方没有传入 [HidTransport] 时，用 `HidController`
 * 组装系统实现（或不可用时的空实现），并随组合生命周期启停。
 *
 * 与键盘页 `rememberKeyboardTransport` 完全同构。
 */
@Composable
internal fun rememberTrackpadTransport(viewModel: MainViewModel): HidTransport {
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
internal fun rememberTrackpadVibrator(): Vibrator? {
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
