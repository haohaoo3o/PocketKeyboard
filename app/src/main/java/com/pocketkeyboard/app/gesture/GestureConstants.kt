package com.pocketkeyboard.app.gesture

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 五指手势引擎的全部判定阈值（集中在此，便于真机调试）。
 *
 * 调参指引：
 * - 觉得「收缩 / 张开」太难触发 → 调小 [PINCH_SHRINK_RATIO] / [SPREAD_GROW_RATIO]；
 * - 觉得「整体横滑」太灵敏 → 调大 [SWIPE_DISTANCE]；
 * - 真机上五指经常凑不齐 → 调大 [FINGER_GATHER_RENEW_MS] / [FINGER_GATHER_MAX_MS]。
 *   注意凑指窗口同时决定触控板层要等多久：窗口内已按下的手指一旦明显在动
 *   （超过 [FINGER_GATHER_MOVE_SLOP]）且没有新手指继续落下，五指层会**立刻**放手，
 *   因此调大这两个值不会给单指触控板操作带来延迟，只会让五指手势更容易凑齐。
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
     * 凑指窗口的**续期**步长：每有新手指落下，就把截止时间推到「该手指落下时刻 + 本时长」。
     *
     * 为什么不能用一个固定的总窗口：人手放下 5 根手指总有先后（熟练 80ms、认真摆位 200ms+），
     * 固定 60ms 意味着**人类几乎不可能凑齐**，五指挥势层永远等不到第 5 根手指。
     * 改成「每来一根就续期」后，慢速摆指也能凑齐，而已经按下的手指只要明显在动
     * （见 [FINGER_GATHER_MOVE_SLOP]）又会立刻放手，触控板手势不会被拖慢。
     */
    const val FINGER_GATHER_RENEW_MS: Long = 300L

    /**
     * 凑指窗口的**总上限**：第一根手指落下后，最多等这么久就必须给出裁决。
     *
     * 防止「每隔 250ms 补一根手指」把等待无限续期；超过上限仍不足 5 指即放手。
     */
    const val FINGER_GATHER_MAX_MS: Long = 800L

    /**
     * 仲裁的**安全超时**：触控板层等待五指层裁决的最长等待时间。
     *
     * 正常流程里五指层一定会在 [FINGER_GATHER_MAX_MS] 内给出裁决（claimFiveFinger 或
     * releaseToTrackpad），这个值只是兜底——万一 `Modifier.pocketGestures` 没挂载
     * （调试时 enabled = false），触控板层等这么久后也会自己接管，不会永久卡死。
     * 它**不是**仲裁窗口本身：仲裁是事件驱动的，五指层一放手触控板层立刻接管。
     */
    const val ARBITRATION_SAFETY_TIMEOUT_MS: Long = 1_500L

    /**
     * 凑指期的「明显在动」阈值：已按下的手指相对基准位置位移超过它、且没有新手指继续落下，
     * 就判定「这是一次正在进行的触控板手势」，五指层立刻释放给触控板层。
     *
     * 取 24dp（与 dock 边缘内滑的触发距离同量级）：远大于摆指时的指尖抖动，
     * 又小到「按下即拖动」的触控板操作几乎察觉不到延迟。基准位置每来一根新手指就重设一次，
     * 所以「慢慢把 5 根手指摆开」不会被误判成拖动。
     */
    val FINGER_GATHER_MOVE_SLOP: Dp = 24.dp

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
