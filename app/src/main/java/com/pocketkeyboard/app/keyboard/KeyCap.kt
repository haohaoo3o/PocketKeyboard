package com.pocketkeyboard.app.keyboard

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketkeyboard.app.gesture.PocketKeyGestureHandler
import com.pocketkeyboard.app.gesture.pocketKeyGestures
import com.pocketkeyboard.app.ui.theme.PureBlack
import com.pocketkeyboard.app.ui.theme.PureWhite
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 键帽按下时的底色：比纯黑略亮一档（iOS 键盘按下态同思路），
 * 配合顶部受光高光与底部细亮线，形成「键帽被按下去」的下陷感。
 */
private val PressedKeyBackground = Color(0xFF1A1A1E)

/** 键帽按下的缩放比例（下陷 + 回弹）。 */
private const val PRESSED_SCALE = 0.94f

/** 媒体图标的绘制视口（24 × 24，与 Material 图标网格一致，绘制时整体缩放到键帽内）。 */
private const val ICON_VIEWPORT = 24f

/**
 * 键帽文字样式：关闭系统字体 padding（`includeFontPadding = false`）。
 *
 * 安卓默认的字体 padding 会让单行文字的视觉重心偏离格子中心（需求 5：字母必须在
 * 格子内水平 + 垂直居中）。关掉后 Text 的测量高度等于字形实际高度，配合外层
 * `Box(contentAlignment = Center)` 才是真正的居中。
 */
private val KeyCapTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * 单个键帽：黑底白字、与相邻键帽**几乎无缝**（gap = 0），仅靠按压下陷与
 * 1dp 渐变分隔细线区分边界（需求 5）。
 *
 * ## 无缝 + 细分隔线 + 下陷
 * - 静止态：纯黑 `#000000` 底 + 白色（或偏好色）文字；相邻键帽之间画一条 1dp、
 *   白色 10% 透明度、从一端淡出的渐变细线（见 [KeySeamLines]），远看无缝、近看可辨；
 * - 按下态：整体缩放到 [PRESSED_SCALE]，底色变 [PressedKeyBackground]，
 *   顶部叠加白色渐变高光、底部一条细亮线（`Modifier.sunkenEdges`），
 *   视觉上像键帽沉入键槽；松手后 spring 回弹（分隔线不随缩放移动）；
 * - 触觉：按下时按 [HapticScale] 档位反馈（中档走系统
 *   `HapticFeedbackConstants.KEYBOARD_TAP`，弱 / 强档走 Vibrator 振幅）。
 *
 * ## 与五指挥势层的关系
 * 每个键帽都挂 `Modifier.pocketKeyGestures`（见 gesture/KeyPointerInput.kt）：
 * 只响应单指；一旦按下指针数达到 5 就停止消费、放弃本轮按下（`onAbandoned`），
 * 把事件放行给页面容器最底层的五指挥势层。**键盘层因此不会消费五指挥势**。
 *
 * @param spec 键位描述（见 [KeySpec]）
 * @param fnActive fn 是否活跃（按住或粘滞）：F 行改显示 F1–F12 大字号本义标签，
 *                 fn / Globe 键整颗高亮
 * @param highlighted 该键是否处于「激活 / 锁定」态（融合页修饰键排的锁定修饰键）：
 *                     整颗键帽按下陷底色 + 高亮边，与 fn 高亮视觉一致
 * @param showSeams 是否绘制键帽之间的渐变分隔细线（87 键键盘与融合页修饰键排开启；
 *                  小键盘 sheet 等紧凑布局可关闭）
 * @param textColor 键帽文字颜色（fn + C 循环：白 / 橙 / 红）
 * @param textSize 键帽字号档位（fn + S 循环：小 / 中 / 大）
 * @param onPress 单指按下（已发送 HID 报告 / 已触发本机功能）
 * @param onRelease 单指抬起（补发松键）
 * @param onAbandoned 按下指针数达到 5、本次按下被五指挥势作废时触发
 *                    （fn 需要整体取消而不是切换粘滞，故与 onRelease 分开）
 */
@Composable
fun KeyCap(
    spec: KeySpec,
    fnActive: Boolean,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    showSeams: Boolean = false,
    textColor: Color = PureWhite,
    textSize: KeyTextSize = KeyTextSize.MEDIUM,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {},
    onAbandoned: () -> Unit = {},
) {
    var pressed by remember { mutableStateOf(false) }
    var abandoned by remember { mutableStateOf(false) }

    val pressedNow = pressed && !abandoned
    val scale by animateFloatAsState(
        targetValue = if (pressedNow) PRESSED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "keyCapScale",
    )

    // fn 活跃时 fn / Globe 键常亮高亮（粘滞视觉状态）；融合页修饰键排的锁定态走 highlighted
    val fnHighlight = fnActive && spec.style == KeyStyle.FN
    val sunken = pressedNow || fnHighlight || highlighted

    // F 行：fn 活跃时改显示 F1–F12 本义大字号标签，否则显示媒体图标
    val showNativeLabel = fnActive && spec.action is KeyAction.FunctionKey

    val baseSize = when (textSize) {
        KeyTextSize.SMALL -> 13.sp
        KeyTextSize.MEDIUM -> 16.sp
        KeyTextSize.LARGE -> 20.sp
    }
    val labelSize = if (spec.style == KeyStyle.NORMAL) baseSize else baseSize * 0.8f
    val subLabelSize = labelSize * 0.55f
    val iconSize = baseSize.value.dp * 1.2f
    val contentDesc = spec.contentDescRes?.let { stringResource(it) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            // 键帽之间的渐变分隔细线（需求 5）。必须挂在 graphicsLayer **之前**：
            // 该节点是 graphicsLayer 的父节点，画的是格子原始坐标——按下时键帽内容
            // 缩放到 0.94，分隔线留在原位，网格不随下陷动画晃动
            .then(
                if (showSeams) {
                    Modifier.drawWithContent {
                        drawContent()
                        drawKeySeamLines()
                    }
                } else {
                    Modifier
                },
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(if (sunken) PressedKeyBackground else PureBlack)
            .then(if (sunken) Modifier.sunkenEdges() else Modifier)
            .then(
                if (contentDesc != null) {
                    Modifier.semantics { this.contentDescription = contentDesc }
                } else {
                    Modifier
                },
            )
            .pocketKeyGestures(
                handler = PocketKeyGestureHandler(
                    onTouchDown = {
                        // 视觉按下：立刻生效，不受五指防误触门控影响（门控只推迟
                        // 真正的激活）。5 指手势里这颗键会被作废，下陷动画随之回弹
                        pressed = true
                        abandoned = false
                    },
                    onPress = {
                        // 真正激活（发 HID 报告 / 触发本机功能）。这之前还有一道
                        // 「五指防误触门控」——5 指凑齐时整颗键作废，不会走到这里
                        onPress()
                    },
                    onRelease = {
                        pressed = false
                        onRelease()
                    },
                    onAbandoned = {
                        // 按下指针数达到 5：本次按下被判定为五指挥势，作废按键。
                        // 门控期间从未调用过 onPress，因此这里不需要补发松键
                        abandoned = true
                        pressed = false
                        onAbandoned()
                    },
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            showNativeLabel -> Text(
                text = stringResource(spec.labelRes),
                color = textColor,
                fontSize = labelSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = KeyCapTextStyle,
            )

            spec.icon != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MediaIconGlyph(
                    icon = spec.icon,
                    modifier = Modifier.size(iconSize),
                    color = textColor,
                    contentDescription = contentDesc,
                )
                // 苹果布局：媒体图标下方的小字 f1–f12
                val subLabelRes = spec.subLabelRes
                if (subLabelRes != null) {
                    Text(
                        text = stringResource(subLabelRes),
                        color = textColor.copy(alpha = 0.5f),
                        fontSize = subLabelSize,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        style = KeyCapTextStyle,
                    )
                }
            }

            else -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Shift 上档字符小字叠在字母上方（苹果式键帽）
                val subLabelRes = spec.subLabelRes
                if (subLabelRes != null) {
                    Text(
                        text = stringResource(subLabelRes),
                        color = textColor.copy(alpha = 0.5f),
                        fontSize = subLabelSize,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        style = KeyCapTextStyle,
                    )
                }
                Text(
                    text = stringResource(spec.labelRes),
                    color = textColor,
                    fontSize = labelSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    style = KeyCapTextStyle,
                )
            }
        }
    }
}

/**
 * 键帽格子的两条渐变分隔细线（需求 5）：右缘垂直线（左右键之分隔，自上而下淡出）
 * + 下缘水平线（上下行之分隔，自左向右淡出）。
 *
 * 线宽 1dp、白色 10% 透明度，向格子内缩半个线宽——屏幕最右 / 最下边缘的键帽
 * 画出来也不会被裁掉半根而显得突兀。
 */
private fun DrawScope.drawKeySeamLines() {
    val lineWidth = SEAM_LINE_WIDTH.toPx()
    drawVerticalSeamLine(
        x = size.width - lineWidth,
        width = lineWidth,
        height = size.height,
    )
    drawHorizontalSeamLine(
        y = size.height - lineWidth,
        width = size.width,
        height = lineWidth,
    )
}

/**
 * 「键帽下沉」的边缘效果：顶部白色受光渐变 + 底部一条细亮线（键槽底边）。
 */
private fun Modifier.sunkenEdges(): Modifier = this.drawWithContent {
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

/**
 * 媒体 / 系统图标：纯白线条矢量，按 [ICON_VIEWPORT] 视口绘制后整体缩放到键帽内。
 *
 * 不引 Material Icons Extended（会显著增大包体），这里用 Compose 图形 API 手绘，
 * 与「黑白极简、OLED 省电」的主题保持一致。
 */
@Composable
fun MediaIconGlyph(
    icon: KeyIcon,
    modifier: Modifier = Modifier,
    color: Color = PureWhite,
    contentDescription: String? = null,
) {
    Canvas(
        modifier = modifier,
        // 整个键帽的无障碍描述由外层 Box 的 semantics 提供，这里给空串避免重复朗读
        contentDescription = contentDescription.orEmpty(),
        onDraw = {
            val unit = min(size.width, size.height) / ICON_VIEWPORT
            // pivot 必须是 Offset.Zero：DrawScope.scale 默认绕画布中心缩放，
            // 会把 [0, ICON_VIEWPORT] 视口坐标整体推出画布（F 行图标只剩碎片），
            // 与 PairingScreen.drawPlatformIconPath 同类问题
            scale(scaleX = unit, scaleY = unit, pivot = Offset.Zero) {
                when (icon) {
                    KeyIcon.VOLUME_UP -> drawSpeaker(color, waves = 2)
                    KeyIcon.VOLUME_DOWN -> drawSpeaker(color, waves = 1)
                    KeyIcon.MUTE -> drawMute(color)
                    KeyIcon.BRIGHTNESS_DOWN -> drawSun(
                        color = color,
                        coreRadius = 3.2f,
                        rayInner = 5.4f,
                        rayOuter = 7.6f,
                    )
                    KeyIcon.BRIGHTNESS_UP -> drawSun(
                        color = color,
                        coreRadius = 4.2f,
                        rayInner = 7.2f,
                        rayOuter = 10.4f,
                    )
                    KeyIcon.PREVIOUS_TRACK -> drawPreviousTrack(color)
                    KeyIcon.NEXT_TRACK -> drawNextTrack(color)
                    KeyIcon.PLAY_PAUSE -> drawPlayPause(color)
                    KeyIcon.STOP -> drawStop(color)
                    KeyIcon.EJECT -> drawEject(color)
                    KeyIcon.SLEEP -> drawSleep(color)
                    KeyIcon.POWER -> drawPower(color)
                    KeyIcon.GLOBE -> drawGlobe(color)
                    KeyIcon.WIN -> drawWinLogo(color)
                }
            }
        },
    )
}

// ------------------------------------------------------------------ 图标绘制（24 × 24 视口）

/** 扬声器（朝右）。 */
private val SpeakerPath: Path = Path().apply {
    moveTo(3f, 9f)
    lineTo(6.6f, 9f)
    lineTo(11.5f, 4f)
    lineTo(11.5f, 20f)
    lineTo(6.6f, 15f)
    lineTo(3f, 15f)
    close()
}

private fun DrawScope.drawSpeaker(color: Color, waves: Int) {
    drawPath(SpeakerPath, color)
    if (waves >= 1) {
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(12f, 7f),
            size = Size(10f, 10f),
            style = Stroke(width = 1.7f, cap = StrokeCap.Round),
        )
    }
    if (waves >= 2) {
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(14.5f, 4f),
            size = Size(15f, 16f),
            style = Stroke(width = 1.7f, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawMute(color: Color) {
    drawPath(SpeakerPath, color)
    drawLine(color, Offset(15f, 8f), Offset(21f, 16f), strokeWidth = 1.8f, cap = StrokeCap.Round)
    drawLine(color, Offset(21f, 8f), Offset(15f, 16f), strokeWidth = 1.8f, cap = StrokeCap.Round)
}

private fun DrawScope.drawSun(
    color: Color,
    coreRadius: Float,
    rayInner: Float,
    rayOuter: Float,
) {
    drawCircle(color = color, radius = coreRadius, center = Offset(12f, 12f))
    for (index in 0 until 8) {
        val angle = Math.toRadians((index * 45).toDouble())
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        drawLine(
            color = color,
            start = Offset(12f + dx * rayInner, 12f + dy * rayInner),
            end = Offset(12f + dx * rayOuter, 12f + dy * rayOuter),
            strokeWidth = 1.6f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawPreviousTrack(color: Color) {
    drawLine(color, Offset(4f, 6f), Offset(4f, 18f), strokeWidth = 2.2f, cap = StrokeCap.Round)
    drawPath(
        Path().apply {
            moveTo(8f, 6f)
            lineTo(17f, 12f)
            lineTo(8f, 18f)
            close()
        },
        color,
    )
}

private fun DrawScope.drawNextTrack(color: Color) {
    drawPath(
        Path().apply {
            moveTo(16f, 6f)
            lineTo(7f, 12f)
            lineTo(16f, 18f)
            close()
        },
        color,
    )
    drawLine(color, Offset(20f, 6f), Offset(20f, 18f), strokeWidth = 2.2f, cap = StrokeCap.Round)
}

private fun DrawScope.drawPlayPause(color: Color) {
    drawPath(
        Path().apply {
            moveTo(5f, 5f)
            lineTo(16f, 12f)
            lineTo(5f, 19f)
            close()
        },
        color,
    )
    drawLine(color, Offset(19f, 5f), Offset(19f, 19f), strokeWidth = 2.2f, cap = StrokeCap.Round)
    drawLine(color, Offset(22f, 5f), Offset(22f, 19f), strokeWidth = 2.2f, cap = StrokeCap.Round)
}

private fun DrawScope.drawStop(color: Color) {
    drawRoundRect(
        color = color,
        topLeft = Offset(6f, 6f),
        size = Size(12f, 12f),
        cornerRadius = CornerRadius(2f),
    )
}

private fun DrawScope.drawEject(color: Color) {
    drawPath(
        Path().apply {
            moveTo(12f, 4f)
            lineTo(4f, 13f)
            lineTo(20f, 13f)
            close()
        },
        color,
    )
    drawLine(
        color,
        Offset(4f, 16.5f),
        Offset(20f, 16.5f),
        strokeWidth = 2.4f,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawSleep(color: Color) {
    // 月牙：外圆减内圆（PathOperation.Difference），颜色随键帽偏好色
    val outer = Path().apply { addOval(Rect(Offset(12f, 12f), 7f)) }
    val inner = Path().apply { addOval(Rect(Offset(15.4f, 9.8f), 6f)) }
    val crescent = Path().apply { op(outer, inner, PathOperation.Difference) }
    drawPath(crescent, color)
}

private fun DrawScope.drawPower(color: Color) {
    drawArc(
        color = color,
        startAngle = -60f,
        sweepAngle = 240f,
        useCenter = false,
        topLeft = Offset(5f, 5f),
        size = Size(14f, 14f),
        style = Stroke(width = 2.2f, cap = StrokeCap.Round),
    )
    drawLine(color, Offset(12f, 3f), Offset(12f, 9.5f), strokeWidth = 2.2f, cap = StrokeCap.Round)
}

private fun DrawScope.drawGlobe(color: Color) {
    drawCircle(
        color = color,
        radius = 8f,
        center = Offset(12f, 12f),
        style = Stroke(width = 1.8f),
    )
    drawArc(
        color = color,
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = Offset(4f, 8.5f),
        size = Size(16f, 7f),
        style = Stroke(width = 1.5f),
    )
    drawLine(color, Offset(12f, 4f), Offset(12f, 20f), strokeWidth = 1.5f)
}

private fun DrawScope.drawWinLogo(color: Color) {
    val square = 7.2f
    val gap = 1.6f
    drawRect(color, topLeft = Offset(4f, 4f), size = Size(square, square))
    drawRect(color, topLeft = Offset(4f + square + gap, 4f), size = Size(square, square))
    drawRect(color, topLeft = Offset(4f, 4f + square + gap), size = Size(square, square))
    drawRect(
        color,
        topLeft = Offset(4f + square + gap, 4f + square + gap),
        size = Size(square, square),
    )
}
