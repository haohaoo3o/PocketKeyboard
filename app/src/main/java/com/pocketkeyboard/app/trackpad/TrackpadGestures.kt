package com.pocketkeyboard.app.trackpad

import com.pocketkeyboard.app.hid.MouseButton
import com.pocketkeyboard.app.ui.DevicePlatform
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 触控板手势的判定参数（纯 Kotlin，无 Compose / Android 依赖，便于单元测试）。
 *
 * 需要「密度」的量（触摸 slop、挥手距离）不走这里，而是由 UI 层用 [TrackpadThresholds]
 * 换算成 px 后注入，本对象只放无量纲比例与上限。
 */
object TrackpadGestureConstants {

    /** 指针位移放大倍数：手指移动 1px，指针移动 1.5px（接近 Windows  Precision Touchpad 默认手感）。 */
    const val MOVE_SENSITIVITY: Float = 1.5f

    /** 单次事件最大指针位移（HID 鼠标报告是单字节有符号，超出会被裁剪，这里提前裁避免方向失真）。 */
    const val MOVE_MAX_PER_EVENT: Int = 120

    /** 多少像素手指位移折算成 1 格滚轮。 */
    const val SCROLL_PX_PER_NOTCH: Float = 12f

    /** 单次事件最大滚轮格数（防止一次大幅滑动刷屏）。 */
    const val SCROLL_MAX_NOTCHES_PER_EVENT: Int = 10

    /** 捏合缩放档位步长：指尖平均间距每变化 15% 发 1 格 ctrl+滚轮。 */
    const val ZOOM_STEP: Float = 0.15f

    /** 进入「捏合中」状态的死区：间距变化超过 6% 即认为在捏合，此后不再滚动。 */
    const val ZOOM_DEADZONE_RATIO: Float = 1.06f

    /** 双指旋转档位步长：每旋转 15° 发 1 格 ctrl+滚轮。 */
    const val ROTATE_STEP_DEG: Float = 15f

    /** 进入「旋转中」状态的死区：累计旋转超过 8° 即认为在旋转，此后不再滚动。 */
    const val ROTATE_DEADZONE_DEG: Float = 8f

    /** 单指点击的最长时长（超过视为长按，不产生点击）。 */
    const val TAP_MAX_DURATION_MS: Long = 500L
}

/**
 * 以 px 为单位的判定阈值，由 UI 层按屏幕密度构造后注入 [TrackpadGestureTracker]。
 *
 * @param tapSlopPx 点击判定的最大质心位移
 * @param swipeDistancePx 三指挥手（任务视图 / 切换应用 / 切换空间）的触发距离
 */
data class TrackpadThresholds(
    val tapSlopPx: Float,
    val swipeDistancePx: Float,
)

/**
 * 一次手势的识别结果。
 *
 * 本密封接口是「触控板层 → HID 层」的唯一出口：手势引擎只产出语义，
 * 由 [TrackpadHidActions] 翻译成 [com.pocketkeyboard.app.hid.HidTransport] 调用，
 * 因此手势逻辑可以在纯 JVM 单测里完整驱动（用一个假 transport 断言发送序列）。
 */
sealed interface TrackpadGesture {

    /** 指针相对位移（已按 [TrackpadGestureConstants.MOVE_SENSITIVITY] 放大并裁剪）。 */
    data class Move(val dx: Int, val dy: Int) : TrackpadGesture

    /** 滚轮，单位「格」；dy 正向下，dx 正向右（水平滚动走 AC Pan 报告）。 */
    data class Scroll(val dx: Int, val dy: Int) : TrackpadGesture

    /**
     * 捏合缩放 / 双指旋转，映射为 ctrl + 滚轮。
     *
     * @param notches 正数放大，负数缩小
     */
    data class Zoom(val notches: Int) : TrackpadGesture

    /** 单击（单指点击 = 左键，双指点击 = 右键）。 */
    data class Click(val button: MouseButton) : TrackpadGesture

    /** 四指点击 = Win 键（Windows 模式，发送 LeftGUI 修饰键后立刻松开）。 */
    data object WinKey : TrackpadGesture

    /** 三指上滑 = 任务视图（Windows 模式，映射 Win + Tab）。 */
    data object TaskView : TrackpadGesture

    /** 三指下滑 = 显示桌面（Windows 模式，映射 Win + D）。 */
    data object ShowDesktop : TrackpadGesture

    /** 三指左右滑 = 切换应用（Windows 模式，映射 Alt + Tab / Alt + Shift + Tab）。 */
    data class SwitchApp(val forward: Boolean) : TrackpadGesture

    /** 三指上滑 = Mission Control（Apple 模式，映射 ctrl + ↑）。 */
    data object MissionControl : TrackpadGesture

    /** 三指左右滑 = 切换全屏空间（Apple 模式，映射 ctrl + ← / →）。 */
    data class SwitchSpace(val forward: Boolean) : TrackpadGesture

    /**
     * 四指捏合 = Launchpad（Apple 模式）。
     *
     * **预留、当前不发送**：Launchpad 没有标准 HID 用法，macOS 也不接受任何
     * 「显示 Launchpad」的按键用法（F4 由键盘固件自己解释，发给外接主机无效），
     * 因此本项只作为语义保留，[TrackpadHidActions] 收到它时什么都不做。
     */
    data object Launchpad : TrackpadGesture
}

/**
 * 一个指针事件周期内、触控板层关心的手指快照。
 *
 * @param trackpadFingers 触控区手指数（**不含**按住底部点击区的手指）
 * @param buttonHeld 当前被按住的底部点击区（左半 = 左键 / 右半 = 右键，null 表示没有）；
 *    按住时的滑动 = 对应键拖拽
 * @param centroidX 触控区手指质心横坐标（px）
 * @param centroidY 触控区手指质心纵坐标（px）
 * @param spread 指尖到质心的平均距离（px），少于 2 指时为 0
 * @param rotationDeg 双指连线角度（度），非 2 指时为 0
 * @param uptimeMillis 该快照对应事件的最新指针时间戳
 */
data class FingerSample(
    val trackpadFingers: Int,
    val buttonHeld: MouseButton?,
    val centroidX: Float,
    val centroidY: Float,
    val spread: Float,
    val rotationDeg: Float,
    val uptimeMillis: Long,
)

/**
 * 触控板手势状态机：喂进来 [FingerSample]，吐出去 [TrackpadGesture]。
 *
 * ## 设计要点
 *
 * 1. **纯 Kotlin**：不碰 Compose / Android，因此单指拖动、双指滚动、捏合缩放、
 *    三指挥手、点击判定全部可以在 JVM 单测里逐条验证；
 * 2. **一次手势一个实例**：由 `Modifier.trackpadGestures` 在每轮
 *    `awaitEachGesture` 里新建，手势结束（所有手指抬起）后调 [finish] 做点击判定；
 * 3. **平台分支只有三指与四指两处**：1 指 / 2 指的行为在 Windows 与 Apple 下完全一致
 *    （指针位移、滚动、捏合缩放都是跨平台共识），差异集中在系统级手势的映射目标上。
 *
 * ## 手势清单
 *
 * | 指数 | Windows（platform == OTHER） | Apple（platform == APPLE） |
 * | --- | --- | --- |
 * | 1 | 拖动 = 指针位移；轻点 = 左键 | 同左 |
 * | 2 | 拖动 = 滚动；捏合 = ctrl+滚轮；轻点 = 右键 | 同左（外加旋转 = ctrl+滚轮） |
 * | 3 | 上滑 = 任务视图；下滑 = 显示桌面；左右滑 = 切换应用 | 上滑 = Mission Control；左右滑 = 切换空间；下滑未映射 |
 * | 4 | 点击 = Win 键 | 捏合 = Launchpad（预留不发送） |
 *
 * @param platform 当前控制目标平台，决定三指 / 四指手势的映射
 * @param thresholds px 级阈值（触摸 slop、挥手距离）
 */
internal class TrackpadGestureTracker(
    private val platform: DevicePlatform,
    private val thresholds: TrackpadThresholds,
) {

    private var initialized = false
    private var previousFingers = 0

    private var firstUptime = 0L
    private var lastUptime = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private var startSpread = 0f
    private var lastRotation = 0f
    private var rotationAccum = 0f
    private var rotationNotches = 0
    private var zoomNotches = 0
    private var pinchActive = false

    private var swipeFired = false
    private var maxFingers = 0

    /**
     * 当前「手指数稳定阶段」里质心走过的总路程（px）。
     *
     * 为什么按阶段算而不是从手势起点算：双指点击时两根手指是一先一后落下的，
     * 第二根落下那一刻质心会从「第一根的位置」跳到「两根的中点」——这个跳变是
     * **摆位**不是移动，不能算进点击判定的位移里。手指数一变就开一个新阶段、
     * 路程重新累计，于是「双指分开摆好不动」依然是点击，「双指一起滑动」依然是滚动。
     */
    private var phasePath = 0f

    /**
     * 本次手势**所有**「手指数稳定阶段」累计的质心路程（px）。
     *
     * 为什么累计而不是取最大阶段：只记最大阶段时，「第一阶段走 10px、第二阶段再走
     * 10px」累计 20px 的手指位移会被当成没动，双指点击判定偏松。按阶段切分只是为了
     * 排除「第二根手指落下那一刻的质心跳变」（摆位），每段真实位移都要计入。
     */
    private var totalPhasePath = 0f

    /**
     * 喂入一个快照，返回本次要发送的手势（null 表示这一拍没有可发送的手势）。
     *
     * 调用方负责在返回非 null 时把它交给 [TrackpadGestureHandler]。
     */
    fun onSample(sample: FingerSample): TrackpadGesture? {
        // 只有点击区的手指（例如单手按住左半区）：触控板层不产出任何手势，
        // 按键的按下 / 松开由点击区自己的 pointerInput 负责
        if (sample.trackpadFingers == 0) return null

        if (!initialized) {
            initialize(sample)
            return null
        }

        maxFingers = maxOf(maxFingers, sample.trackpadFingers)
        lastUptime = sample.uptimeMillis

        // 手指数发生变化（例如双指滚动时抬起一根）：质心 / 间距 / 角度的基准全部作废，
        // 重新取基准并开一个新阶段，避免用「上一拍的几何」算出一次巨大的跳变位移。
        // 换阶段这一拍的质心跳变是摆位不计入，但之前各段的真实路程要累计下来
        if (sample.trackpadFingers != previousFingers) {
            totalPhasePath += phasePath
            phasePath = 0f
            reBaseline(sample)
            return null
        }

        // 本阶段质心走过的路程（点击判定用）
        phasePath += hypot(sample.centroidX - lastX, sample.centroidY - lastY)

        val gesture = when {
            // 按住物理按键区 + 在触控区滑动 = 对应键拖拽（按键的按住状态由按键区维护）
            sample.buttonHeld != null -> move(sample)

            sample.trackpadFingers == 1 -> move(sample)

            sample.trackpadFingers == 2 -> twoFinger(sample)

            sample.trackpadFingers == 3 -> threeFinger(sample)

            // 4 指：只参与「点击 = Win 键 / 捏合 = Launchpad」判定，这里只负责识别捏合
            else -> {
                if (isPinching(sample)) pinchActive = true
                null
            }
        }

        lastX = sample.centroidX
        lastY = sample.centroidY
        return gesture
    }

    /**
     * 所有手指抬起后调用：做「点击」判定。
     *
     * 判定条件：本次手势**没有**产出过挥手、没有进入捏合 / 旋转、且质心在每个
     * 「手指数稳定阶段」里走过的路程（累计）都没超过触摸 slop。
     * 四指在 Windows 下是 Win 键，在 Apple 下是 Launchpad（预留）。
     */
    fun finish(): TrackpadGesture? {
        if (!initialized || maxFingers == 0) return null
        // 发生过挥手 → 这是一次系统手势，不是点击
        if (swipeFired) return null
        // 捏合 / 旋转过 → 同上
        if (pinchActive) return null
        // 收尾时把最后一个阶段的路程并进来
        totalPhasePath += phasePath
        if (totalPhasePath > thresholds.tapSlopPx) return null

        return when (maxFingers) {
            1 -> {
                // 单指轻点 = 左键；按住超过 TAP_MAX_DURATION_MS 视为长按，不补点击
                val duration = lastUptime - firstUptime
                if (duration <= TrackpadGestureConstants.TAP_MAX_DURATION_MS) {
                    TrackpadGesture.Click(MouseButton.LEFT)
                } else {
                    null
                }
            }

            // 双指轻点 = 右键（Windows Precision Touchpad 与 Apple 触控板的共同行为）
            2 -> TrackpadGesture.Click(MouseButton.RIGHT)

            // 三指轻点：两个平台都没有约定行为，不映射
            3 -> null

            // 四指：Windows = Win 键；Apple = Launchpad（预留，不发送）
            else -> when (platform) {
                DevicePlatform.OTHER -> TrackpadGesture.WinKey
                DevicePlatform.APPLE -> TrackpadGesture.Launchpad
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun initialize(sample: FingerSample) {
        initialized = true
        previousFingers = sample.trackpadFingers
        // 第一个快照就要把手指数记下来：一次「按下即抬起」的轻点可能没有任何中间事件，
        // 漏记的话 finish() 会认为本次手势没发生过，点击就丢了
        maxFingers = sample.trackpadFingers
        firstUptime = sample.uptimeMillis
        lastUptime = sample.uptimeMillis
        startX = sample.centroidX
        startY = sample.centroidY
        lastX = sample.centroidX
        lastY = sample.centroidY
        startSpread = sample.spread
        lastRotation = sample.rotationDeg
    }

    private fun reBaseline(sample: FingerSample) {
        previousFingers = sample.trackpadFingers
        lastX = sample.centroidX
        lastY = sample.centroidY
        // 手指数变了，双指几何（间距 / 旋转）的基准随之作废
        startSpread = sample.spread
        lastRotation = sample.rotationDeg
        rotationAccum = 0f
        rotationNotches = 0
        zoomNotches = 0
        pinchActive = false
    }

    /** 单指（或按住按键时的拖动）→ 指针位移。 */
    private fun move(sample: FingerSample): TrackpadGesture? {
        val dx = sample.centroidX - lastX
        val dy = sample.centroidY - lastY
        if (dx == 0f && dy == 0f) return null
        val mx = scaleAxis(dx)
        val my = scaleAxis(dy)
        if (mx == 0 && my == 0) return null
        return TrackpadGesture.Move(mx, my)
    }

    /** 双指 → 缩放 / 旋转（都映射 ctrl+滚轮），否则滚动。 */
    private fun twoFinger(sample: FingerSample): TrackpadGesture? {
        // ① 旋转量累积（角度差归一化到 (-180, 180]，避免 ±180° 翻转时爆一个巨值）
        val rotationDelta = normalizeAngle(sample.rotationDeg - lastRotation)
        lastRotation = sample.rotationDeg
        if (rotationDelta != 0f) rotationAccum += rotationDelta

        // ② 判定是否进入「捏合 / 旋转中」：进入后本次手势不再滚动
        if (!pinchActive) {
            val ratio = spreadRatio(sample)
            pinchActive = abs(rotationAccum) >= TrackpadGestureConstants.ROTATE_DEADZONE_DEG ||
                (ratio != null && isBeyondDeadzone(ratio))
        }

        if (pinchActive) {
            // ③ 旋转档位：每 ROTATE_STEP_DEG 发 1 格
            val rotationTarget = notchesFor(rotationAccum, TrackpadGestureConstants.ROTATE_STEP_DEG)
            if (rotationTarget != rotationNotches) {
                val delta = rotationTarget - rotationNotches
                rotationNotches = rotationTarget
                return TrackpadGesture.Zoom(delta)
            }
            // ④ 缩放档位：间距每变化 ZOOM_STEP 发 1 格
            val ratio = spreadRatio(sample)
            if (ratio != null) {
                val zoomTarget = zoomNotchFor(ratio)
                if (zoomTarget != zoomNotches) {
                    val delta = zoomTarget - zoomNotches
                    zoomNotches = zoomTarget
                    return TrackpadGesture.Zoom(delta)
                }
            }
            return null
        }

        return scroll(sample)
    }

    /** 双指拖动 → 滚轮（水平滚动走 AC Pan，垂直走普通滚轮）。 */
    private fun scroll(sample: FingerSample): TrackpadGesture? {
        val dx = sample.centroidX - lastX
        val dy = sample.centroidY - lastY
        val nx = notchesForAxis(dx)
        val ny = notchesForAxis(dy)
        if (nx == 0 && ny == 0) return null
        return TrackpadGesture.Scroll(nx, ny)
    }

    /** 三指 → 平台级挥手，一次手势只触发一次（避免一次长滑连切多个应用 / 空间）。 */
    private fun threeFinger(sample: FingerSample): TrackpadGesture? {
        if (swipeFired) return null
        val dx = sample.centroidX - startX
        val dy = sample.centroidY - startY
        val threshold = thresholds.swipeDistancePx

        val gesture = when {
            abs(dx) > threshold && abs(dx) > abs(dy) -> when (platform) {
                // Windows：三指左右滑 = 切换应用（Alt + Tab）
                DevicePlatform.OTHER ->
                    TrackpadGesture.SwitchApp(forward = dx > 0f)

                // Apple：三指左右滑 = 切换全屏空间（ctrl + ←/→）
                DevicePlatform.APPLE ->
                    TrackpadGesture.SwitchSpace(forward = dx > 0f)
            }

            abs(dy) > threshold -> when (platform) {
                DevicePlatform.OTHER -> if (dy < 0f) {
                    // 三指上滑 = 任务视图
                    TrackpadGesture.TaskView
                } else {
                    // 三指下滑 = 显示桌面
                    TrackpadGesture.ShowDesktop
                }

                DevicePlatform.APPLE -> if (dy < 0f) {
                    // 三指上滑 = Mission Control
                    TrackpadGesture.MissionControl
                } else {
                    // Apple 三指下滑在系统里是 App Exposé，没有等价的 HID 用法，不映射
                    null
                }
            }

            else -> null
        }

        if (gesture != null) swipeFired = true
        return gesture
    }

    private fun spreadRatio(sample: FingerSample): Float? =
        if (startSpread > 0f && sample.spread > 0f) sample.spread / startSpread else null

    private fun isBeyondDeadzone(ratio: Float): Boolean =
        ratio >= TrackpadGestureConstants.ZOOM_DEADZONE_RATIO ||
            ratio <= 1f / TrackpadGestureConstants.ZOOM_DEADZONE_RATIO

    private fun isPinching(sample: FingerSample): Boolean =
        spreadRatio(sample)?.let { isBeyondDeadzone(it) } ?: false

    private fun scaleAxis(deltaPx: Float): Int =
        (deltaPx * TrackpadGestureConstants.MOVE_SENSITIVITY)
            .roundToInt()
            .coerceIn(
                -TrackpadGestureConstants.MOVE_MAX_PER_EVENT,
                TrackpadGestureConstants.MOVE_MAX_PER_EVENT,
            )

    private fun notchesForAxis(deltaPx: Float): Int =
        (deltaPx / TrackpadGestureConstants.SCROLL_PX_PER_NOTCH)
            .roundToInt()
            .coerceIn(
                -TrackpadGestureConstants.SCROLL_MAX_NOTCHES_PER_EVENT,
                TrackpadGestureConstants.SCROLL_MAX_NOTCHES_PER_EVENT,
            )

    companion object {

        /** 角度差归一化到 (-180, 180]。 */
        fun normalizeAngle(degrees: Float): Float {
            var value = degrees
            while (value > 180f) value -= 360f
            while (value <= -180f) value += 360f
            return value
        }

        /** 双指连线角度（度），用于旋转识别。 */
        fun angleBetween(x1: Float, y1: Float, x2: Float, y2: Float): Float =
            Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()

        /**
         * 把累计量换算成「档位」：正数向下取整、负数向上取整，
         * 保证 ratio = 1 / 1.15 时得到 -1 而不是 0。
         */
        fun notchesFor(accum: Float, step: Float): Int =
            if (accum >= 0f) {
                floor(accum / step.toDouble()).toInt()
            } else {
                -floor(-accum / step.toDouble()).toInt()
            }

        /** 间距比例 → 缩放档位（对数刻度，1.15 倍一档）。 */
        fun zoomNotchFor(ratio: Float): Int {
            if (ratio <= 0f) return 0
            val step = 1f + TrackpadGestureConstants.ZOOM_STEP
            return if (ratio >= 1f) {
                floor(ln(ratio) / ln(step)).toInt()
            } else {
                -floor(ln(1f / ratio) / ln(step)).toInt()
            }
        }
    }
}
