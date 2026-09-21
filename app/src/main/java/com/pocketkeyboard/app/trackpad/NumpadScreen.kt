package com.pocketkeyboard.app.trackpad

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketkeyboard.app.R
import com.pocketkeyboard.app.hid.HidTransport
import com.pocketkeyboard.app.keyboard.KeyAction
import com.pocketkeyboard.app.keyboard.KeyboardReportEngine
import com.pocketkeyboard.app.keyboard.KeyCap
import com.pocketkeyboard.app.keyboard.KeyTextSize
import com.pocketkeyboard.app.keyboard.RowItem
import com.pocketkeyboard.app.keyboard.performKeyHaptic
import com.pocketkeyboard.app.ui.theme.PureBlack
import com.pocketkeyboard.app.ui.theme.PureWhite

/** sheet 高度占屏幕的比例。 */
private const val SHEET_HEIGHT_FRACTION = 0.62f

/** sheet 滑入 / 滑出时长（苹果式 sheet 的手感：快出、缓停）。 */
private const val SHEET_SLIDE_MS = 320

/** sheet 内容淡入时长（比滑动短，先看到轮廓再看到内容）。 */
private const val SHEET_FADE_MS = 160

/** 遮罩淡入 / 淡出时长。 */
private const val SHEET_SCRIM_MS = 200

/** 苹果 sheet 的缓动曲线（iOS 13+ 的 sheet present 曲线）。 */
private val AppleSheetEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * 数字小键盘 sheet：从屏幕底部滑入，盖在触控板之上。
 *
 * ## 交互
 * - 点击上方半透明遮罩 → 收起，回到触控板；
 * - 按系统返回键 → 收起（[BackHandler]）；
 * - 右上角「完成」按钮 → 收起；
 * - 每个键帽挂 `Modifier.pocketKeyGestures`，因此**五指收缩 / 张开 / 横滑依然有效**
 *   （五指挥势层挂在更外层，读 Initial pass，不受 sheet 影响）。
 *
 * ## 为什么手写而不用 Material3 的 ModalBottomSheet
 *
 * ModalBottomSheet 自带拖拽把手、圆角与 Material 配色，和「纯黑 #000000 + 白字 +
 * 与触控板无缝」的设计语言对不上；这里用 [AnimatedVisibility] + 苹果式曲线自己滑入，
 * 既能精确控制曲线与时长，也保证 sheet 是纯黑底、没有把手、没有圆角间隙。
 *
 * @param visible 是否展示
 * @param transport HID 传输（契约接口）
 * @param textColor 键帽文字颜色（与键盘页同一份偏好：白 / 橙 / 红）
 * @param textSize 键帽字号档位（与键盘页同一份偏好：小 / 中 / 大）
 * @param onDismiss 请求收起
 */
@Composable
fun NumpadSheet(
    visible: Boolean,
    transport: HidTransport,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = PureWhite,
    textSize: KeyTextSize = KeyTextSize.MEDIUM,
) {
    // 系统返回键：sheet 打开时先收起 sheet，而不是直接退出页面
    BackHandler(enabled = visible) { onDismiss() }

    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩：纯黑半透明，点一下收起。不加在 sheet 之上，因此不会拦截 sheet 内的触摸
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(SHEET_SCRIM_MS)),
            exit = fadeOut(animationSpec = tween(SHEET_SCRIM_MS)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PureBlack.copy(alpha = 0.62f))
                    .pointerInput(onDismiss) {
                        detectTapGestures(onTap = { onDismiss() })
                    },
            )
        }

        // sheet 本体：贴底、从屏幕外滑入
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(
                animationSpec = tween(durationMillis = SHEET_SLIDE_MS, easing = AppleSheetEasing),
            ) { fullHeight -> fullHeight } + fadeIn(animationSpec = tween(SHEET_FADE_MS)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = SHEET_SLIDE_MS, easing = AppleSheetEasing),
            ) { fullHeight -> fullHeight } + fadeOut(animationSpec = tween(SHEET_FADE_MS)),
        ) {
            NumpadScreen(
                transport = transport,
                onClose = onDismiss,
                textColor = textColor,
                textSize = textSize,
            )
        }
    }
}

/**
 * 数字小键盘内容：4 列 × 6 行，黑底白字、与相邻键帽无缝（gap = 0）。
 *
 * 键帽直接用键盘页的 [KeyCap]（同一份视觉 / 按压动画 / 无障碍实现），并沿用键盘页的
 * 键帽颜色 / 字号偏好（[textColor] / [textSize]，由调用方从 `KeyboardPreferencesStore`
 * 读取后传入），报告发送复用键盘页的 [KeyboardReportEngine]（修饰键位图 + 最多 6 键同按），
 * 因此小键盘与键盘页行为完全一致：按下发报告、抬手发松键、被五指挥势作废时补发松键。
 */
@Composable
fun NumpadScreen(
    transport: HidTransport,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = PureWhite,
    textSize: KeyTextSize = KeyTextSize.MEDIUM,
) {
    val view = LocalView.current
    val vibrator = rememberTrackpadVibrator()
    val engine = remember(transport) { KeyboardReportEngine(transport) }

    // 页面消失时把所有按住的键松开，避免卡键
    DisposableEffect(engine) {
        onDispose { engine.releaseAll() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(SHEET_HEIGHT_FRACTION)
            .background(PureBlack)
            // 底部安全区：与触控板页一致的 28dp，避开系统手势条
            .padding(bottom = TrackpadMetrics.SAFE_ZONE_HEIGHT),
    ) {
        NumpadHeader(onClose = onClose)

        val rows = remember { NumpadLayout.rows() }
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                row.items.forEach { item ->
                    when (item) {
                        is RowItem.Key -> {
                            val usage = (item.spec.action as? KeyAction.Usage)?.usage
                            KeyCap(
                                spec = item.spec,
                                fnActive = false,
                                textColor = textColor,
                                textSize = textSize,
                                modifier = Modifier
                                    .weight(item.widthU)
                                    .fillMaxHeight(),
                                onPress = {
                                    performKeyHaptic(view, vibrator)
                                    if (usage != null) engine.keyDown(usage)
                                },
                                onRelease = {
                                    if (usage != null) engine.keyUp(usage)
                                },
                                onAbandoned = {
                                    // 五指挥势作废本次按下：补发松键，避免卡键
                                    if (usage != null) engine.keyUp(usage)
                                },
                            )
                        }

                        // 小键盘布局没有上下叠放键槽（方向键区在键盘页），这里只是穷尽分支
                        is RowItem.Stacked -> Unit
                    }
                }
            }
        }
    }
}

/** 小键盘顶部：标题 + 右上角「完成」按钮（收起 sheet，回到触控板）。 */
@Composable
private fun NumpadHeader(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.numpad_title),
            color = PureWhite.copy(alpha = 0.45f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.weight(1f))
        NumpadCloseButton(onClick = onClose)
    }
}

/**
 * 小键盘顶部右侧的「完成」按钮：收起 sheet、回到触控板。
 *
 * 为什么不复用 [NumpadToggleButton]：那个按钮的无障碍描述是「打开或收起数字小键盘」、
 * 图标是点阵 + 「123」，语义是「开关」；用它当返回按钮时，视觉与读屏都会误导
 * （用户听到的是「打开或收起」而不是「收起」）。关闭是一个单向动作，单独一个
 * 纯文字按钮，读屏文案也单独给。
 */
@Composable
private fun NumpadCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val vibrator = rememberTrackpadVibrator()
    val label = stringResource(R.string.numpad_done)
    val description = stringResource(R.string.cd_numpad_close)

    Text(
        text = label,
        color = PureWhite.copy(alpha = 0.55f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = modifier
            // padding 在 clickable 之前：热区含内边距（约 48×30dp），
            // 满足无障碍最小触摸目标；视觉位置不变（clip 仍包住文字+内边距）
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable {
                performKeyHaptic(view, vibrator)
                onClick()
            }
            .semantics { contentDescription = description },
    )
}
