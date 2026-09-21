package com.pocketkeyboard.app.keyboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketkeyboard.app.ui.theme.PureWhite
import kotlinx.coroutines.delay

/** HUD 请求：fn + 空格的亮度档位（[level] 为 [BRIGHTNESS_LEVELS] 下标，0 = 100%）。 */
data class BrightnessHudRequest(val level: Int, val label: String)

/** HUD 请求：fn + C / S / V 的档位文字提示。 */
data class MessageHudRequest(val message: String)

/** 键盘页 HUD 的两种形态。 */
sealed interface KeyboardHudRequest {
    data class Brightness(val request: BrightnessHudRequest) : KeyboardHudRequest
    data class Message(val request: MessageHudRequest) : KeyboardHudRequest
}

/** HUD 可见时长（与五指挥势 HUD 一致的节奏）。 */
private const val HUD_VISIBLE_MS = 1_000L

/** HUD 淡入淡出时长。 */
private const val HUD_FADE_MS = 220

/**
 * fn + 空格循环的屏幕背光档位（本机窗口亮度，非被控设备）。
 *
 * 下标 0 = 100%，依次 75% / 50% / 25% / 最低。`WindowManager.LayoutParams.screenBrightness`
 * 只影响本 Activity 窗口，不需要 WRITE_SETTINGS 权限。
 */
internal val BRIGHTNESS_LEVELS = floatArrayOf(1f, 0.75f, 0.5f, 0.25f, 0.02f)

/**
 * 键盘页 HUD 层：屏幕中央、iOS 风格。
 *
 * - 亮度 HUD：黑底半透明圆角 + 白色太阳图标 + 5 格刻度条（点亮格数随档位递减）；
 * - 文字 HUD：黑底半透明圆角 + 白字（键帽颜色 / 字号 / 震感档位）。
 *
 * 挂在键盘最上层但**不加任何 pointerInput**，对触摸完全透明，不会挡住按键，
 * 也不会影响底层五指挥势层（见 gesture 包的仲裁说明）。
 */
@Composable
fun KeyboardHudLayer(
    hud: KeyboardHudRequest?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 1 秒后淡出（由调用方清空 hud 状态）
    LaunchedEffect(hud) {
        if (hud != null) {
            delay(HUD_VISIBLE_MS)
            onDismiss()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (hud) {
            is KeyboardHudRequest.Brightness -> BrightnessHud(
                level = hud.request.level,
                label = hud.request.label,
            )

            is KeyboardHudRequest.Message -> MessageHud(message = hud.request.message)

            null -> Unit
        }
    }
}

/**
 * iOS 风格亮度 HUD：黑底半透明圆角、白色太阳图标 + 刻度条。
 *
 * 刻度共 5 格，点亮格数 = 5 - [level]（100% 全亮，最低只亮 1 格）。
 */
@Composable
private fun BrightnessHud(
    level: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = HUD_FADE_MS, easing = FastOutSlowInEasing),
        label = "brightnessHudAlpha",
    )

    Row(
        modifier = modifier
            .alpha(alpha)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaIconGlyph(
            icon = if (level >= 3) KeyIcon.BRIGHTNESS_DOWN else KeyIcon.BRIGHTNESS_UP,
            modifier = Modifier.size(38.dp),
            color = PureWhite,
            contentDescription = label,
        )
        Spacer(modifier = Modifier.width(18.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 刻度条：5 格，点亮格数随亮度档位递减
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val litCount = BRIGHTNESS_LEVELS.size - level
                for (index in BRIGHTNESS_LEVELS.indices) {
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .height(64.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (index < litCount) {
                                    PureWhite
                                } else {
                                    PureWhite.copy(alpha = 0.25f)
                                },
                            ),
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = label,
                color = PureWhite,
                fontSize = 13.sp,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 文字 HUD：黑底半透明圆角 + 白字（颜色 / 字号 / 震感档位）。 */
@Composable
private fun MessageHud(
    message: String,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = HUD_FADE_MS, easing = FastOutSlowInEasing),
        label = "messageHudAlpha",
    )

    Box(
        modifier = modifier
            .alpha(alpha)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = PureWhite,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}
