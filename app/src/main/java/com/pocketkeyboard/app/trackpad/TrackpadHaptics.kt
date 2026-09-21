package com.pocketkeyboard.app.trackpad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.pocketkeyboard.app.keyboard.HapticScale
import com.pocketkeyboard.app.keyboard.performKeyHaptic

/**
 * 触控板手势的**触觉反馈**策略与「一次手势只反馈一次」的状态。
 *
 * ## 放在这里的原因
 *
 * 触控板页（`TrackpadScreen`）和竖屏融合页（`FusedControlScreen`）都通过
 * `Modifier.trackpadGestures` 驱动 `TrackpadGestureTracker`，触觉反馈挂在**手势修饰符内部**，
 * 于是两个页面自动共用同一套反馈，不会出现「触控板页有震感、融合页没有」的分裂。
 * 底部左右点击区的按下反馈见 [TrackpadClickZone]（它自己就是共享组件）。
 *
 * ## 强度档位
 *
 * 直接复用键盘页的 [performKeyHaptic]，它按 [HapticScale] 档位执行：
 * 关 = 不反馈、中 = 系统标准 `KEYBOARD_TAP`、弱 / 强 = 8ms/70 与 18ms/255 的单次震动。
 * [HapticScale] 是与键盘页 fn+V 循环同步的单例，因此触控板页的震感强弱与键盘页始终一致。
 *
 * ## 哪些手势给反馈
 *
 * - **给**：点击（单指 = 左键 / 双指 = 右键 / 点击区左右键）、双指滚动**开始**、
 *   捏合缩放、三指 / 四指系统手势——都是「一次明确的动作达成」；
 * - **不给**：指针连续位移（[TrackpadGesture.Move]）。一次拖动会刷出几十次 Move，
 *   每拍都震等于持续震动；也不给滚动持续期间——滚动只在该次手势的**第一拍**反馈一次。
 */
internal fun TrackpadGesture.wantsHapticFeedback(): Boolean = when (this) {
    // 连续位移：一次拖动几十拍，反馈会变成持续震动
    is TrackpadGesture.Move -> false

    // 滚动由调用方保证一次手势只反馈第一拍（见 TrackpadGestureHaptics）
    is TrackpadGesture.Scroll -> true

    // 一次性达成的手势：识别成功即反馈
    is TrackpadGesture.Zoom -> true
    is TrackpadGesture.Click -> true
    is TrackpadGesture.SwitchApp -> true
    is TrackpadGesture.SwitchSpace -> true
    TrackpadGesture.WinKey -> true
    TrackpadGesture.TaskView -> true
    TrackpadGesture.ShowDesktop -> true
    TrackpadGesture.MissionControl -> true
    // 四指捏合在 Apple 模式下识别成功但主机没有等价用法：反馈的是「识别成功」，
    // 与手势被识别的事实对齐（用户确实做了一个四指捏合）
    TrackpadGesture.Launchpad -> true
}

/**
 * 一次触控板手势的触觉反馈状态机。
 *
 * 之所以需要状态：滚动 / 缩放是**连续**手势，一个手势周期内会产出多拍
 * [TrackpadGesture.Scroll]，只在第一拍反馈一次才有「滚动开始了」的触感，
 * 否则整段滚动都在震。
 *
 * 与 [TrackpadGestureTracker] 一样「一次手势一个实例」：由
 * [detectTrackpadGestures] 在每轮 `awaitEachGesture` 里新建，因此滚动反馈的
 * 「只发一次」配额每次新手势都会重置。
 *
 * @param perform 真正执行反馈的回调（通常是把 [performKeyHaptic] 包一层）；
 *    用 lambda 而不是直接持有 View / Vibrator，便于在纯 JVM 单测里计数
 */
internal class TrackpadGestureHaptics(
    private val perform: () -> Unit,
) {

    private var scrollFeedbackFired = false

    /** 一次手势识别成功：按策略决定是否给触觉反馈。 */
    fun onGesture(gesture: TrackpadGesture) {
        if (!gesture.wantsHapticFeedback()) return
        if (gesture is TrackpadGesture.Scroll) {
            if (scrollFeedbackFired) return
            scrollFeedbackFired = true
        }
        perform()
    }
}

/**
 * 触控板手势的触觉反馈动作：按 [HapticScale] 档位执行，与键盘页 / 底部点击区
 * 完全同一套强度（键盘页 fn+V 循环切换的档位对这里同样生效）。
 *
 * 只在 [HapticScale.strength] 为 OFF 时什么都不做。
 *
 * 返回稳定的 `() -> Unit`（`remember` 住），供 `Modifier.trackpadGestures` 塞进
 * `pointerInput`：手势协程每轮 `awaitEachGesture` 用它新建一个 [TrackpadGestureHaptics]，
 * 于是滚动「只反馈一次」的配额每次新手势都会重置。
 */
@Composable
internal fun rememberTrackpadHapticFeedback(): () -> Unit {
    val view = LocalView.current
    val vibrator = rememberTrackpadVibrator()
    return remember(view, vibrator) {
        // HapticScale.strength.value 在调用时读取：档位变化不用重建本 lambda
        { performKeyHaptic(view, vibrator) }
    }
}
