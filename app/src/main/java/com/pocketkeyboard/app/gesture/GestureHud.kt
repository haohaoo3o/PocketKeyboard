package com.pocketkeyboard.app.gesture

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 一次 HUD 展示请求。[id] 每次自增，保证相同文案重复触发时也能重新开始倒计时。 */
data class HudRequest(val id: Long, val message: String)

/**
 * 手势视觉反馈的状态持有者。
 *
 * 由页面容器创建并通过 [rememberGestureHudState] 记住，手势回调（`PocketGestureHandler`）
 * 直接调用 [show]，UI 侧用 [GestureFeedback] 渲染。
 *
 * 这样拆开的原因：手势识别跑在 `pointerInput` 协程里，拿不到 Composable 上下文，
 * 也不能直接读 `strings.xml`；文案由 MainActivity 在组合期解析好后传进来。
 */
class GestureHudState {

    /** 当前 HUD 请求，null 表示已淡出。 */
    var hud by mutableStateOf<HudRequest?>(null)
        private set

    private var nextId = 0L

    /** 显示一条 HUD 文字提示，[GestureConstants.HUD_VISIBLE_MS] 后淡出。 */
    fun show(message: String) {
        hud = HudRequest(nextId++, message)
    }

    /** 立即收起 HUD（淡出动画由 [GestureFeedback] 负责）。 */
    fun hideHud() {
        hud = null
    }
}

/** 创建并记住一个 [GestureHudState]。 */
@Composable
fun rememberGestureHudState(): GestureHudState = remember { GestureHudState() }

/**
 * 手势视觉反馈层：屏幕中央的 HUD 文字提示。
 *
 * 挂在页面容器里、画在最上层（但**不加任何 pointerInput**，因此对触摸完全透明，
 * 不会挡住下方的键盘 / 触控板，也不会挡住底层 `Modifier.pocketGestures`）。
 */
@Composable
fun GestureFeedback(
    state: GestureHudState,
    modifier: Modifier = Modifier,
) {
    val hud = state.hud

    // HUD：展示 1 秒后开始淡出
    LaunchedEffect(hud) {
        if (hud != null) {
            kotlinx.coroutines.delay(GestureConstants.HUD_VISIBLE_MS)
            state.hideHud()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        GestureHudPill(
            message = hud?.message,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * 屏幕中央的文字提示 HUD：黑底半透明圆角、白字，出现 1 秒后淡出。
 */
@Composable
private fun GestureHudPill(
    message: String?,
    modifier: Modifier = Modifier,
) {
    val targetAlpha = if (message != null) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(
            durationMillis = GestureConstants.HUD_FADE_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "gestureHudAlpha",
    )
    if (message == null && alpha <= 0.01f) return

    Row(
        modifier = modifier
            .alpha(alpha)
            .background(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
    }
}
