package com.pocketkeyboard.app.trackpad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import com.pocketkeyboard.app.gesture.GestureConstants
import com.pocketkeyboard.app.gesture.PocketKeyGestureHandler
import com.pocketkeyboard.app.gesture.pocketKeyGestures
import com.pocketkeyboard.app.keyboard.performKeyHaptic
import com.pocketkeyboard.app.ui.theme.PureBlack
import com.pocketkeyboard.app.ui.theme.PureWhite

/**
 * 触控板底部「短竖线」分割线。
 *
 * 触控板页与竖屏融合布局的触控板区共用这一个组件：分割线的宽 / 高 / 透明度全部来自
 * [TrackpadMetrics]，两边画出来的永远是同一条线。融合布局只需
 * `import com.pocketkeyboard.app.trackpad.TrackpadDivider`。
 *
 * ## 用法
 *
 * 分割线必须落在「点击带」的正中央（= 触控区的水平中线、点击带的垂直中线）。
 * 最省事的写法是把点击带本身作为一个 `contentAlignment = Alignment.Center` 的 Box，
 * 再把本组件放进去：
 *
 * ```kotlin
 * Box(
 *     modifier = Modifier
 *         .offset { IntOffset(zones.band.left.roundToInt(), zones.band.top.roundToInt()) }
 *         .size(zones.band.width.toDp(), zones.band.height.toDp()),
 *     contentAlignment = Alignment.Center,
 * ) {
 *     TrackpadDivider()
 * }
 * ```
 *
 * 本组件**不挂任何 pointerInput**：它对触摸完全透明，落在分割线上的手指由它下面
 * 的点击区（[TrackpadClickZone]）接手。
 *
 * @param modifier 位置修饰符（调用方负责把线摆到点击带中央）
 * @param width 线宽，默认 [TrackpadMetrics.CLICK_DIVIDER_WIDTH]（1.5dp）
 * @param height 线高，默认 [TrackpadMetrics.CLICK_DIVIDER_HEIGHT]（32dp）
 * @param color 线的颜色，默认白色 [TrackpadMetrics.CLICK_DIVIDER_ALPHA] 透明度
 */
@Composable
fun TrackpadDivider(
    modifier: Modifier = Modifier,
    width: Dp = TrackpadMetrics.CLICK_DIVIDER_WIDTH,
    height: Dp = TrackpadMetrics.CLICK_DIVIDER_HEIGHT,
    color: Color = PureWhite.copy(alpha = TrackpadMetrics.CLICK_DIVIDER_ALPHA),
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .background(color),
    )
}

/**
 * 底部点击带的左半 / 右半：一个**不可见**的点击区。
 *
 * - 轻点 → 由调用方发一次「按下 + 松开」= 左键 / 右键单击；
 * - 按住不放、另一根手指在触控区滑动 → 对应键的拖拽（由触控区手势层实现，
 *   它把按在本区的手指计入 [FingerSample.buttonHeld]）；
 * - 被五指挥势作废（按下指针数达到 5）时同样补发松键，避免卡键。
 *
 * ## 视觉
 *
 * 静态时整块区域与纯黑背景完全融为一体——不画边框、不印「左键 / 右键」文字，
 * 用户只看得到点击带中央那条 [TrackpadDivider] 短竖线，由此知道「这里可以点左右键」。
 * 按下时才露出与键盘页 [com.pocketkeyboard.app.keyboard.KeyCap] 同款的下陷反馈。
 *
 * @param onPress 按下：调用方发送 `sendMouseButton(button, true)`
 * @param onRelease 抬起或被五指挥势作废：调用方发送 `sendMouseButton(button, false)`
 */
@Composable
fun TrackpadClickZone(
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    var abandoned by remember { mutableStateOf(false) }
    val pressedNow = pressed && !abandoned

    val view = LocalView.current
    val vibrator = rememberTrackpadVibrator()

    Box(
        modifier = modifier
            .background(if (pressedNow) ClickZonePressedBackground else PureBlack)
            .then(if (pressedNow) Modifier.clickZoneSunkenEdges() else Modifier)
            .pocketKeyGestures(
                // 鼠标左右键不是字符键，误触代价低，但「按住拖动」对按下延迟敏感，
                // 因此门控比字符键短（见 GestureConstants.KEY_PRESS_FIVE_FINGER_GUARD_MS 的取舍说明）
                pressGuardMs = GestureConstants.CLICK_ZONE_PRESS_GUARD_MS,
                handler = PocketKeyGestureHandler(
                    onTouchDown = {
                        pressed = true
                        abandoned = false
                    },
                    onPress = {
                        performKeyHaptic(view, vibrator)
                        onPress()
                    },
                    onRelease = {
                        pressed = false
                        onRelease()
                    },
                    onAbandoned = {
                        // 按下指针数达到 5：本次按下被判定为五指挥势，作废并补发松键。
                        // 门控期间若已激活过，这里补发的松键是必要的兜底
                        abandoned = true
                        pressed = false
                        onRelease()
                    },
                ),
            ),
    )
}

/** 点击区按下时的底色（比纯黑略亮一档，与键盘页键帽按下态一致）。 */
private val ClickZonePressedBackground = Color(0xFF1A1A1E)

/** 「区域下沉」的边缘效果：顶部受光渐变 + 底部一条细亮线，与键盘页 [com.pocketkeyboard.app.keyboard.KeyCap] 同款。 */
private fun Modifier.clickZoneSunkenEdges(): Modifier = this.drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.verticalGradient(
            0f to PureWhite.copy(alpha = 0.22f),
            0.4f to Color.Transparent,
        ),
        size = size,
    )
    drawRect(
        color = PureWhite.copy(alpha = 0.12f),
        topLeft = Offset(0f, size.height - 1.5f),
        size = Size(size.width, 1.5f),
    )
}
