package com.pocketkeyboard.app.gesture

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset
import com.pocketkeyboard.app.ui.AppMode

/**
 * 苹果式页面转场曲线。
 *
 * 苹果 UIKit 的 spring 大致是「刚度 MediumLow + 无回弹阻尼」的手感：起步快、收尾稳、
 * 没有明显过冲。这里统一收敛到 [AppleSpring]，页面切换与覆盖转场都复用同一组曲线，
 * 保证整个 App 的动效呼吸感一致。
 */
object AppleSpring {

    /** Float（透明度、缩放等）用。 */
    val float: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** IntOffset（横向位移）用，带上可见性阈值避免最后一像素的抖动。 */
    val intOffset: SpringSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )
}

/**
 * 页面切换（Pairing / Keyboard / Trackpad / Numpad）用的「推拉」转场。
 *
 * - 模式序号变大（PAIRING → KEYBOARD → TRACKPAD → NUMPAD）：新页面从右侧推入、旧页面被推向左侧；
 * - 模式序号变小：方向相反。
 *
 * 用法：
 * ```kotlin
 * AnimatedContent(
 *     targetState = mode,
 *     transitionSpec = { applePageTransitionSpec() },
 *     label = "page",
 * ) { current -> ... }
 * ```
 */
fun applePageTransitionSpec(): AnimatedContentTransitionScope<AppMode>.() -> ContentTransform = {
    // AppMode 的声明顺序即底部栏 + 手势的顺序，序号增大视为「前进」
    val forward = targetState.ordinal >= initialState.ordinal
    val slide = if (forward) {
        AnimatedContentTransitionScope.SlideDirection.Left
    } else {
        AnimatedContentTransitionScope.SlideDirection.Right
    }
    (
        slideIntoContainer(slide, AppleSpring.intOffset) +
            fadeIn(animationSpec = AppleSpring.float)
        ) togetherWith (
        slideOutOfContainer(slide, AppleSpring.intOffset) +
            fadeOut(animationSpec = AppleSpring.float)
        )
}
