package com.pocketkeyboard.app.trackpad

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.pocketkeyboard.app.gesture.GestureArbiter
import com.pocketkeyboard.app.gesture.GestureOwner
import com.pocketkeyboard.app.gesture.awaitGestureOwner
import com.pocketkeyboard.app.hid.MouseButton
import com.pocketkeyboard.app.ui.DevicePlatform
import kotlin.math.hypot

/**
 * 触控板页的手势业务回调。
 *
 * 与键盘页的 [com.pocketkeyboard.app.gesture.PocketKeyGestureHandler] 同一风格：
 * 手势引擎只负责「识别」，本回调负责「识别出来之后做什么」（转发给
 * [TrackpadHidActions] 发送 HID 报告）。
 */
class TrackpadGestureHandler(
    val onGesture: (TrackpadGesture) -> Unit = {},
)

/** 触控板页的尺寸常量（dp）。UI 布局与手势几何共用同一份定义，避免两边算出的区域不一致。 */
internal object TrackpadMetrics {

    /** 底部安全区高度：显示当前控制设备名，同时避开系统手势条。 */
    val SAFE_ZONE_HEIGHT = 28.dp

    /** 物理按键模拟区高度。 */
    val BUTTON_ZONE_HEIGHT = 54.dp

    /** 物理按键模拟区距屏幕底部的距离（安全区 28dp + 12dp 间距）。 */
    val BUTTON_ZONE_BOTTOM_MARGIN = 40.dp

    /** 物理按键模拟区左右外边距。 */
    val BUTTON_ZONE_SIDE_MARGIN = 14.dp

    /** 物理按键模拟区最大宽度（窄屏上按屏宽比例收缩）。 */
    val BUTTON_ZONE_MAX_WIDTH = 180.dp

    /** 物理按键模拟区宽度占触控区宽度的比例。 */
    const val BUTTON_ZONE_WIDTH_FRACTION = 0.40f

    /** 三指挥手（任务视图 / 切换应用 / 切换空间）的触发距离。 */
    val SWIPE_DISTANCE = 120.dp

    /** 点击判定的最大质心位移。 */
    val TAP_SLOP = 12.dp
}

/** 一个矩形区域（px，相对触控区左上角）。 */
internal data class ZoneRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}

/** 触控区底部左右两个物理按键模拟区。 */
internal data class ButtonZones(val left: ZoneRect, val right: ZoneRect) {

    /** 坐标落在哪个按键区；都不在返回 null。 */
    fun zoneOf(x: Float, y: Float): MouseButton? = when {
        left.contains(x, y) -> MouseButton.LEFT
        right.contains(x, y) -> MouseButton.RIGHT
        else -> null
    }

    fun contains(x: Float, y: Float): Boolean = zoneOf(x, y) != null
}

/**
 * 按 [TrackpadMetrics] 计算两个物理按键模拟区的位置（px）。
 *
 * UI 布局（`TrackpadSurface` 里的两个圆角矩形）与手势层（判断某根手指是不是
 * 「按住按键区的手指」）调用的是**同一个函数**，因此永远不会出现「看到的手势区
 * 和画出来的按键区对不上」的情况。
 */
internal fun buttonZones(widthPx: Float, heightPx: Float, density: Density): ButtonZones {
    val zoneWidth = minOf(widthPx * TrackpadMetrics.BUTTON_ZONE_WIDTH_FRACTION,
        with(density) { TrackpadMetrics.BUTTON_ZONE_MAX_WIDTH.toPx() })
    val zoneHeight = with(density) { TrackpadMetrics.BUTTON_ZONE_HEIGHT.toPx() }
    val side = with(density) { TrackpadMetrics.BUTTON_ZONE_SIDE_MARGIN.toPx() }
    val bottom = with(density) { TrackpadMetrics.BUTTON_ZONE_BOTTOM_MARGIN.toPx() }
    val top = heightPx - bottom - zoneHeight
    return ButtonZones(
        left = ZoneRect(side, top, side + zoneWidth, top + zoneHeight),
        right = ZoneRect(widthPx - side - zoneWidth, top, widthPx - side, top + zoneHeight),
    )
}

/**
 * 给触控区挂上「1–4 指触控手势识别层」。
 *
 * ## 与五指挥势层的仲裁（详细说明）
 *
 * 页面结构上，五指挥势层（`Modifier.pocketGestures`，由 MainActivity 挂在承载
 * `AnimatedContent` 的 Box 上）是本层的**父节点**，键盘按键层 / 触控板层都是它的子节点。
 * Compose 的一次 `PointerEvent` 按三个 pass 沿 hit-test 路径下发：
 *
 * ```
 * Initial：根 → 叶   父节点先看到，可以在这里抢先 consume
 * Main   ：叶 → 根   子节点先看到，子 consume 掉的事件父节点仍会收到但 isConsumed == true
 * Final  ：根 → 叶   父节点兜底
 * ```
 *
 * 也就是说 Main pass 里触控板层（子）比五指挥势层（父）**先**拿到事件。如果两边各自为政，
 * 一次「先放一根手指、再快速补四根」的触摸会被触控板层当成单指拖动，同时又被五指层当成
 * 收缩手势，两边同时动作、互相打架。因此本层遵守下面的协作规则：
 *
 * 1. **按下后先挂起，不动手**：拿到第一根手指的 down 之后，立刻调用
 *    [awaitGestureOwner]（内部转调 `GestureArbiter.awaitDecision()` 语义）挂起等待，
 *    在仲裁结果出来之前**不消费任何 change、不产出任何手势**；
 * 2. **五指层先表态 → 放行**：如果 60ms（[com.pocketkeyboard.app.gesture.GestureConstants.ARBITRATION_WINDOW_MS]）
 *    内凑满 5 指，五指挥势层会在 Initial pass 里调用 `claimFiveFinger()`，
 *    [awaitGestureOwner] 立刻返回 [GestureOwner.FIVE_FINGER]，本层直接结束本轮
 *    `awaitEachGesture`、什么都不做，事件自然继续留给五指层；
 * 3. **窗口耗尽 → 接管**：60ms 内没有凑满 5 指，[awaitGestureOwner] 返回
 *    [GestureOwner.TRACKPAD]，本层才开始处理 1–4 指手势；
 * 4. **谁复位**：五指挥势层在「所有手指抬起」后调用 `arbiter.reset()`，
 *    本层不负责复位；但每轮手势开始时会调一次 `arbiter.onGestureStart()` 兜底，
 *    防止上一轮遗留的归属被带到新一轮（详见 [detectTrackpadGestures] 里的注释）。
 *
 * @param platform 当前控制目标平台，决定三指 / 四指手势映射
 * @param handler 手势回调
 * @param arbiter **必须**与 `Modifier.pocketGestures` 使用同一个实例，否则仲裁无效
 * @param physicalButtonsEnabled 是否启用底部物理按键模拟区（Windows 模式为 true）
 * @param thresholds px 级判定阈值
 * @param enabled false 时整个手势层不挂载（调试用）
 */
@Composable
fun Modifier.trackpadGestures(
    platform: DevicePlatform,
    handler: TrackpadGestureHandler,
    arbiter: GestureArbiter,
    thresholds: TrackpadThresholds,
    physicalButtonsEnabled: Boolean,
    enabled: Boolean = true,
): Modifier {
    // rememberUpdatedState：pointerInput 的 key 只有 arbiter / platform / 阈值，
    // handler 每次重组都变，用UpdatedState 保证回调拿到最新实例而不重启手势协程
    val currentHandler by rememberUpdatedState(handler)
    return this.then(
        if (enabled) {
            Modifier.pointerInput(arbiter, platform, thresholds, physicalButtonsEnabled) {
                val density: Density = this
                detectTrackpadGestures(
                    handler = { currentHandler },
                    arbiter = arbiter,
                    platform = platform,
                    thresholds = thresholds,
                    physicalButtonsEnabled = physicalButtonsEnabled,
                    density = density,
                )
            }
        } else {
            Modifier
        },
    )
}

/**
 * 触控板手势主循环。
 *
 * ```
 * awaitEachGesture {                                  // 每轮都从「所有手指抬起」的干净状态开始
 *   awaitTrackpadDown(zones)                          // ① 等第一根手指落下（含按键区的按下）
 *   awaitGestureOwner(arbiter, down.uptimeMillis)     // ② 仲裁：60ms 内凑满 5 指归五指层
 *        ├─ FIVE_FINGER → return，不消费 → 五指层接管
 *        └─ TRACKPAD   → ③ 逐事件喂给 TrackpadGestureTracker
 *   tracker.finish()                                  // ④ 所有手指抬起：点击判定
 * }
 * ```
 */
private suspend fun PointerInputScope.detectTrackpadGestures(
    handler: () -> TrackpadGestureHandler,
    arbiter: GestureArbiter,
    platform: DevicePlatform,
    thresholds: TrackpadThresholds,
    physicalButtonsEnabled: Boolean,
    density: Density,
) {
    awaitEachGesture {
        // 按键区矩形只跟触控区尺寸有关，手势开始前算一次就够（尺寸变化会由重组触发新 pointerInput）
        val zones = if (physicalButtonsEnabled) {
            buttonZones(size.width.toFloat(), size.height.toFloat(), density)
        } else {
            null
        }

        // ① 等第一根手指落下
        val firstDown = awaitTrackpadDown(zones)

        // 兜底复位：把上一轮可能遗留的归属清成「未裁定」，避免新一轮手势被旧结果直接带走。
        // 正常流程里五指挥势层已经在「所有手指抬起」后 reset 过，这里是双保险；
        // 它只可能覆盖「陈旧的 TRACKPAD / FIVE_FINGER」，不可能覆盖本轮刚刚发生的裁定
        // （本轮裁定至少要等第 5 根手指落下，而那一定晚于这里看到的第 1 根手指）。
        arbiter.onGestureStart()

        // ② 仲裁：在五指层表态之前不消费、不动作
        if (awaitGestureOwner(arbiter, firstDown.uptimeMillis) == GestureOwner.FIVE_FINGER) {
            // 归五指层：什么都不做，事件继续留给父节点的五指挥势层
            return@awaitEachGesture
        }

        // ③ 归触控板层：开始跟踪 1–4 指手势
        // 记录每根手指「按下时」的位置：判断它是不是按在物理按键区必须用按下位置，
        // 否则手指一滑出按键区，按住状态就丢了
        val downPositions = HashMap<PointerId, Offset>()
        downPositions[firstDown.id] = firstDown.position

        val tracker = TrackpadGestureTracker(platform, thresholds)
        // 先把第一根手指的按下位置喂给跟踪器：一次「按下即抬起」的轻点可能压根没有
        // 中间事件（DOWN 之后直接 UP），不在这里初始化的话 tracker 会认为本次手势
        // 没有发生过，finish() 也就判不出点击
        tracker.onSample(buildFingerSample(listOf(firstDown), zones, downPositions))

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            event.changes.forEach { change ->
                if (change.pressed && !change.previousPressed) {
                    downPositions[change.id] = change.position
                }
            }
            val sample = buildFingerSample(pressed, zones, downPositions)
            tracker.onSample(sample)?.let { gesture -> handler().onGesture(gesture) }
        }

        // ④ 所有手指抬起：做点击 / 四指轻点判定
        tracker.finish()?.let { gesture -> handler().onGesture(gesture) }
    }
}

/**
 * 等第一根手指落下。
 *
 * 与键盘按键层（`awaitFirstDown(requireUnconsumed = true)`）不同，这里**接受已被消费的
 * 按下**，前提是它落在物理按键模拟区里：按键区的 pointerInput 会 consume 掉自己那份
 * down（否则拖拽时两根手指会互相抢），但触控区仍然必须看到这根手指，才能实现
 * 「按住左键区 + 在触控区滑动 = 左键拖拽」。
 *
 * 其它被消费的按下（例如小键盘 sheet 里的按键、右上角「123」按钮）会被跳过，
 * 这样小键盘打开时按数字键不会顺带把鼠标指针挪动。
 */
private suspend fun AwaitPointerEventScope.awaitTrackpadDown(
    zones: ButtonZones?,
): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { change ->
            if (change.pressed && !change.previousPressed) {
                val inButtonZone = zones != null &&
                    zones.contains(change.position.x, change.position.y)
                if (!change.isConsumed || inButtonZone) return change
            }
        }
    }
}

/**
 * 把一次事件里的按下指针整理成 [FingerSample]。
 *
 * 分区规则：
 * - 按下位置落在物理按键模拟区 → 计入 [FingerSample.buttonHeld]，**不**参与质心 / 间距 /
 *   旋转计算（它是「被按住的按键」，不是「在触控区滑动的手指」）；
 * - 其余按下指针 → 触控区手指，参与全部几何计算。
 */
private fun buildFingerSample(
    pressed: List<PointerInputChange>,
    zones: ButtonZones?,
    downPositions: Map<PointerId, Offset>,
): FingerSample {
    var buttonHeld: MouseButton? = null
    var sumX = 0f
    var sumY = 0f
    var count = 0
    val trackpadChanges = ArrayList<PointerInputChange>(pressed.size)

    for (change in pressed) {
        val down = downPositions[change.id]
        val zone = if (zones != null && down != null) zones.zoneOf(down.x, down.y) else null
        if (zone != null) {
            // 左右键同时按住时以左手区为准（极少见，优先级只影响拖拽用哪个键）
            if (buttonHeld == null) buttonHeld = zone
            continue
        }
        trackpadChanges += change
        sumX += change.position.x
        sumY += change.position.y
        count++
    }

    val uptime = pressed.maxOfOrNull { it.uptimeMillis } ?: 0L
    if (count == 0) {
        return FingerSample(
            trackpadFingers = 0,
            buttonHeld = buttonHeld,
            centroidX = 0f,
            centroidY = 0f,
            spread = 0f,
            rotationDeg = 0f,
            uptimeMillis = uptime,
        )
    }

    val centroidX = sumX / count
    val centroidY = sumY / count
    // 指尖到质心的平均距离：捏合缩放的比例基准
    val spread = if (count >= 2) {
        trackpadChanges.sumOf { change ->
            hypot(
                (change.position.x - centroidX).toDouble(),
                (change.position.y - centroidY).toDouble(),
            )
        }.toFloat() / count
    } else {
        0f
    }
    // 双指连线角度：旋转识别用（多于 2 指时角度没有意义，置 0）
    val rotation = if (count == 2) {
        TrackpadGestureTracker.angleBetween(
            trackpadChanges[0].position.x,
            trackpadChanges[0].position.y,
            trackpadChanges[1].position.x,
            trackpadChanges[1].position.y,
        )
    } else {
        0f
    }

    return FingerSample(
        trackpadFingers = count,
        buttonHeld = buttonHeld,
        centroidX = centroidX,
        centroidY = centroidY,
        spread = spread,
        rotationDeg = rotation,
        uptimeMillis = uptime,
    )
}
