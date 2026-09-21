package com.pocketkeyboard.app.gesture

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 五指手势引擎的全部判定阈值（集中在此，便于真机调试）。
 *
 * 调参指引：
 * - 觉得「收缩 / 张开」太难触发 → 调小 [PINCH_SHRINK_RATIO] / [SPREAD_GROW_RATIO]；
 * - 觉得「整体横滑」太灵敏 → 调大 [SWIPE_DISTANCE]；
 * - 真机上五连击经常凑不齐 → 调大 [FINGER_GATHER_WINDOW_MS]，但注意它同时是触控板层的
 *   仲裁窗口，调大后触控板层会等待更久才开始响应自己的 1–4 指手势。
 */
object GestureConstants {

    /** 触发五指手势所需的同时按下指针数。 */
    const val REQUIRED_FINGER_COUNT: Int = 5

    /**
     * 收缩判定比例：指尖平均间距缩小到初始值的 (1 - 0.30) 以下即判定为「五指收缩」。
     *
     * 例：初始平均间距 400px，则 < 280px 时触发，切换到触控板模式。
     */
    const val PINCH_SHRINK_RATIO: Float = 0.30f

    /**
     * 张开判定比例：指尖平均间距放大到初始值的 (1 + 0.30) 以上即判定为「五指张开」。
     *
     * 例：初始平均间距 400px，则 > 520px 时触发，切换到键盘模式。
     */
    const val SPREAD_GROW_RATIO: Float = 0.30f

    /**
     * 五指整体横滑判定距离：5 个指尖的质心（平均位置）水平位移超过该值即判定为横滑，
     * 在已配对设备间循环切换 [com.pocketkeyboard.app.ui.MainViewModel.activeDevice]。
     */
    val SWIPE_DISTANCE: Dp = 200.dp

    /**
     * 仲裁窗口时长：第一根手指按下后，必须在该时间内凑满 5 指，手势才归五指手势层；
     * 否则归触控板层的 1–4 指手势。详见 [GestureArbiter]。
     */
    const val ARBITRATION_WINDOW_MS: Long = 60L

    /**
     * 五指手势层等待「凑齐 5 指」的最长间隔。
     *
     * 与 [ARBITRATION_WINDOW_MS] 保持一致：超过该时间仍未凑齐 5 指，五指手势层放弃
     * 本次手势并等所有手指抬起，避免和触控板层抢同一次触摸。
     */
    const val FINGER_GATHER_WINDOW_MS: Long = 60L

    /** HUD 文字提示可见时长，到期后开始淡出。 */
    const val HUD_VISIBLE_MS: Long = 1_000L

    /** HUD 淡出动画时长。 */
    const val HUD_FADE_MS: Int = 220

    /** 设备切换「覆盖式」转场中，覆盖层停留时长。 */
    const val DEVICE_SWITCH_COVER_HOLD_MS: Long = 1_000L

    /** 几何量（质心 / 平均间距）的浮点抖动容差，避免把噪点当成位移。 */
    const val GEOMETRY_EPSILON: Float = 0.5f

    /** 一次横滑手势最多触发的设备切换次数（防止一次滑动连续跳多台）。 */
    const val MAX_DEVICE_SWITCHES_PER_GESTURE: Int = 1

}
