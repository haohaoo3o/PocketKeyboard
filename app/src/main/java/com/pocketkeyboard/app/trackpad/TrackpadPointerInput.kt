package com.pocketkeyboard.app.trackpad

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.pocketkeyboard.app.gesture.GestureArbiter
import com.pocketkeyboard.app.gesture.GestureConstants
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

    /**
     * 底部点击带高度：左右两个点击区的高度，也是短竖线的垂直居中基准。
     *
     * 点击带本身不画边框、不印文字，整块区域静态时与纯黑背景融为一体，
     * 只有按下时露出下陷反馈（见 [TrackpadClickZone]）。
     */
    val CLICK_BAND_HEIGHT = 36.dp

    /** 点击带距触控区底部的距离（底部安全区 28dp + 12dp 间距）。 */
    val CLICK_BAND_BOTTOM_MARGIN = 40.dp

    /** 点击带中央短竖线的宽度（1–2dp，纯黑底上一条发丝线）。 */
    val CLICK_DIVIDER_WIDTH = 1.5.dp

    /** 点击带中央短竖线的高度（28–36dp，比点击带略矮，上下各留一点呼吸）。 */
    val CLICK_DIVIDER_HEIGHT = 32.dp

    /** 短竖线的白色透明度（30%–40%：看得见，但绝不抢眼）。 */
    const val CLICK_DIVIDER_ALPHA = 0.35f

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

/**
 * 触控区底部的点击带：整条带（[band]）被中央短竖线分成左右两个点击区（[left] / [right]）。
 *
 * - 点左半 = 左键，点右半 = 右键；
 * - 从左半按住拖动 = 左键拖拽，右半按住拖动 = 右键拖拽；
 * - 两个点击区都不显示任何文字，静态时与纯黑背景融为一体。
 */
internal data class ClickZones(
    val band: ZoneRect,
    val left: ZoneRect,
    val right: ZoneRect,
) {

    /** 坐标落在哪个点击区；都不在返回 null。 */
    fun zoneOf(x: Float, y: Float): MouseButton? = when {
        left.contains(x, y) -> MouseButton.LEFT
        right.contains(x, y) -> MouseButton.RIGHT
        else -> null
    }

    fun contains(x: Float, y: Float): Boolean = zoneOf(x, y) != null
}

/**
 * 按 [TrackpadMetrics] 计算底部点击带与左右两个点击区的位置（px）。
 *
 * 点击带横贯触控区整个宽度、贴在底部（安全区之上），短竖线画在带的正中央
 * （= 触控区的水平中线），于是「左半 = 左键、右半 = 右键」。
 *
 * UI 布局（`TrackpadSurface` 里的两个点击区 + 分割线）与手势层（判断某根手指是不是
 * 「按住点击区的手指」）调用的是**同一个函数**，因此永远不会出现「看到的手势区
 * 和画出来的点击区对不上」的情况。
 *
 * 竖屏融合布局（`FusedControlScreen`）的触控板区也调用本函数：两边共用同一份几何。
 */
internal fun clickZones(widthPx: Float, heightPx: Float, density: Density): ClickZones {
    val bandHeight = with(density) { TrackpadMetrics.CLICK_BAND_HEIGHT.toPx() }
    val bottomMargin = with(density) { TrackpadMetrics.CLICK_BAND_BOTTOM_MARGIN.toPx() }
    // 触控区比点击带还矮的极端情形（超小窗口 / 预览）：贴顶放置，不让区域跑到画面外
    val top = (heightPx - bottomMargin - bandHeight).coerceAtLeast(0f)
    val bottom = minOf(top + bandHeight, heightPx)
    val middle = widthPx / 2f
    return ClickZones(
        band = ZoneRect(0f, top, widthPx, bottom),
        left = ZoneRect(0f, top, middle, bottom),
        right = ZoneRect(middle, top, widthPx, bottom),
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
 * 2. **五指层先表态 → 放行**：凑指窗口内凑满 5 指时，五指挥势层会在 Initial pass 里调用
 *    `claimFiveFinger()`，[awaitGestureOwner] 立刻返回 [GestureOwner.FIVE_FINGER]，
 *    本层直接结束本轮 `awaitEachGesture`、什么都不做，事件自然继续留给五指层；
 * 3. **五指层放手 → 接管**：五指挥势层判定「明确不是五指挥势」（凑指窗口到期 /
 *    手指明显在动 / 全部抬起）时调用 `releaseToTrackpad()`，[awaitGestureOwner] 随即返回
 *    [GestureOwner.TRACKPAD]，本层开始处理 1–4 指手势。**没有固定等待时长**：
 *    裁决是事件驱动的，单指触控板操作因此几乎无延迟；
 * 4. **谁复位**：五指挥势层在「所有手指抬起」后调用 `arbiter.reset()`，
 *    本层不负责复位；但每轮手势开始时会调一次 `arbiter.onGestureStart()` 兜底，
 *    防止上一轮遗留的归属被带到新一轮（详见 [detectTrackpadGestures] 里的注释）。
 *
 * ## 触觉反馈
 *
 * 反馈动作由 [rememberTrackpadHapticFeedback] 提供、策略与「一次手势只反馈一次」的
 * 状态在 [TrackpadGestureHaptics]，本修饰符在每轮手势里驱动它，因此触控板页与
 * 竖屏融合页（`FusedControlScreen`）自动共用同一套反馈与同一档位。
 *
 * @param platform 当前控制目标平台，决定三指 / 四指手势映射
 * @param handler 手势回调
 * @param arbiter **必须**与 `Modifier.pocketGestures` 使用同一个实例，否则仲裁无效
 * @param clickZonesEnabled 是否启用底部的左右点击区（Windows 模式为 true；Apple 模式
 *    单指 = 左键、双指 = 右键，不需要点击区）
 * @param thresholds px 级判定阈值
 * @param enabled false 时整个手势层不挂载（调试用）
 */
@Composable
fun Modifier.trackpadGestures(
    platform: DevicePlatform,
    handler: TrackpadGestureHandler,
    arbiter: GestureArbiter,
    thresholds: TrackpadThresholds,
    clickZonesEnabled: Boolean,
    enabled: Boolean = true,
): Modifier {
    // rememberUpdatedState：pointerInput 的 key 只有 arbiter / platform / 阈值，
    // handler 每次重组都变，用UpdatedState 保证回调拿到最新实例而不重启手势协程
    val currentHandler by rememberUpdatedState(handler)
    // 触觉反馈动作（稳定 lambda）：与键盘页 / 底部点击区共用 HapticScale 档位（fn+V 循环切换）。
    // 反馈的「一次手势只发一次」状态在 detectTrackpadGestures 里每轮新手势重建
    val hapticFeedback = rememberTrackpadHapticFeedback()
    return this.then(
        if (enabled) {
            Modifier.pointerInput(arbiter, platform, thresholds, clickZonesEnabled) {
                val density: Density = this
                detectTrackpadGestures(
                    handler = { currentHandler },
                    arbiter = arbiter,
                    platform = platform,
                    thresholds = thresholds,
                    clickZonesEnabled = clickZonesEnabled,
                    density = density,
                    hapticFeedback = hapticFeedback,
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
 *   awaitTrackpadDown(zones)                          // ① 等第一根手指落下（含点击区里的按下）
 *   awaitGestureOwner(arbiter, down.uptimeMillis)     // ② 仲裁：等五指层的最终裁决
 *        ├─ FIVE_FINGER → return，不消费 → 五指层接管
 *        └─ TRACKPAD   → observedEvents 按顺序补喂 + ③ 逐事件喂给 TrackpadGestureTracker
 *   tracker.finish()                                  // ④ 所有手指抬起：点击判定
 * }
 * ```
 */
private suspend fun PointerInputScope.detectTrackpadGestures(
    handler: () -> TrackpadGestureHandler,
    arbiter: GestureArbiter,
    platform: DevicePlatform,
    thresholds: TrackpadThresholds,
    clickZonesEnabled: Boolean,
    density: Density,
    hapticFeedback: () -> Unit,
) {
    awaitEachGesture {
        // 触觉反馈：一次手势一个实例，「滚动只反馈第一拍」的配额每轮新手势重置
        val haptics = TrackpadGestureHaptics(hapticFeedback)

        // 点击区矩形只跟触控区尺寸有关，手势开始前算一次就够（尺寸变化会由重组触发新 pointerInput）
        val zones = if (clickZonesEnabled) {
            clickZones(size.width.toFloat(), size.height.toFloat(), density)
        } else {
            null
        }

        // ① 等第一根手指落下（返回它所在的事件：同一帧里可能还有别的指针已经按下）
        val firstDown = awaitTrackpadDown(zones)

        // 兜底复位：把上一轮可能遗留的归属清成「未裁定」，避免新一轮手势被旧结果直接带走。
        // 正常流程里五指挥势层已经在「所有手指抬起」后复位，这里是双保险。
        //
        // 例外：首帧事件**批量**落下 ≥5 指（合成注入 / 掌心同帧触地）时，五指层已在本
        // 事件的 Initial pass 里 claimFiveFinger——而 Initial 先于本层的 Main，若此刻照旧
        // 清归属，就会把刚刚做出的 FIVE_FINGER 裁决抹回 UNDECIDED，两边同时动手打架。
        // 这种帧上跳过清零，awaitGestureOwner 直接读到 FIVE_FINGER 并放弃本轮手势。
        val firstFramePressed = firstDown.event.changes.count { it.pressed }
        if (firstFramePressed < GestureConstants.REQUIRED_FINGER_COUNT) {
            arbiter.onGestureStart()
        }

        // ② 仲裁：在五指层表态之前不消费、不动作。
        // 事件驱动：五指层一放手这里就返回，不再有固定 60ms 超时；等待期间读到的事件
        // 全部缓存下来，接管后按原顺序补喂给跟踪器（否则「窗口内就结束」的轻点会丢）。
        val arbitration = awaitGestureOwner(arbiter, firstDown.change.uptimeMillis)
        if (arbitration.owner == GestureOwner.FIVE_FINGER) {
            // 归五指层：什么都不做，事件继续留给父节点的五指挥势层
            return@awaitEachGesture
        }

        // ③ 归触控板层：开始跟踪 1–4 指手势
        // 记录每根手指「按下时」的位置：判断它是不是按在底部点击区必须用按下位置，
        // 否则手指一滑出点击区，按住状态就丢了。
        // 同一帧里已经按下的指针一并记录（例如一根手指先落在屏幕边缘、被 dock 激活层
        // consume 掉 down 的第二根手指）：否则这次手势会被误判成单指，
        // 双指滚动退化成指针拖动、双指轻点退化成左键。
        val downPositions = HashMap<PointerId, Offset>()
        val firstDownPressed = firstDown.event.changes.filter { it.pressed }
        firstDownPressed.forEach { downPositions[it.id] = it.position }

        val tracker = TrackpadGestureTracker(platform, thresholds)
        // 先把按下位置喂给跟踪器：一次「按下即抬起」的轻点可能压根没有
        // 中间事件（DOWN 之后直接 UP），不在这里初始化的话 tracker 会认为本次手势
        // 没有发生过，finish() 也就判不出点击
        tracker.onSample(buildFingerSample(firstDownPressed, zones, downPositions))

        // 把一次指针事件喂给跟踪器（仲裁期间缓存的 + 之后实时的走同一条路）
        fun consume(event: PointerEvent) {
            val pressed = event.changes.filter { it.pressed }
            event.changes.forEach { change ->
                if (change.pressed && !change.previousPressed) {
                    downPositions[change.id] = change.position
                }
            }
            if (pressed.isEmpty()) return
            val gesture = tracker.onSample(buildFingerSample(pressed, zones, downPositions))
            if (gesture != null) {
                haptics.onGesture(gesture)
                handler().onGesture(gesture)
            }
        }

        // 仲裁期间观察到的事件按原顺序补喂：等待时发生的手指位移照样折算成
        // 指针移动 / 滚动，手势接管后不会有跳变
        var gestureRunning = true
        for (event in arbitration.observedEvents) {
            consume(event)
            if (event.changes.none { it.pressed }) {
                gestureRunning = false
                break
            }
        }

        while (gestureRunning) {
            val event = awaitPointerEvent()
            consume(event)
            if (event.changes.none { it.pressed }) gestureRunning = false
        }

        // ④ 所有手指抬起：做点击 / 四指轻点判定
        tracker.finish()?.let { gesture ->
            haptics.onGesture(gesture)
            handler().onGesture(gesture)
        }
    }
}

/**
 * 第一根「归本层」的手指落下时的信息：change 本身 + 它所在的那个 [PointerEvent]。
 *
 * 事件一起返回的理由见 [detectTrackpadGestures] 里对 `firstDownPressed` 的注释。
 */
private class TrackpadFirstDown(
    val change: PointerInputChange,
    val event: PointerEvent,
)

/**
 * 等第一根「归本层」的手指落下。
 *
 * 与键盘按键层（`awaitFirstDown(requireUnconsumed = true)`）不同，这里**接受已被消费的
 * 按下**，前提是它落在底部点击区里：点击区的 pointerInput 会 consume 掉自己那份
 * down（否则拖拽时两根手指会互相抢），但触控区仍然必须看到这根手指，才能实现
 * 「按住左半 + 在触控区滑动 = 左键拖拽」。
 *
 * 其它被消费的按下（例如小键盘 sheet 里的按键、右上角「123」按钮、以及 dock 的
 * 单指边缘内滑层在 Initial pass 里消费掉的那一根）会被跳过，
 * 这样小键盘打开时按数字键不会顺带把鼠标指针挪动。
 */
private suspend fun AwaitPointerEventScope.awaitTrackpadDown(
    zones: ClickZones?,
): TrackpadFirstDown {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { change ->
            if (change.pressed && !change.previousPressed) {
                val inClickZone = zones != null &&
                    zones.contains(change.position.x, change.position.y)
                if (!change.isConsumed || inClickZone) return TrackpadFirstDown(change, event)
            }
        }
    }
}

/**
 * 把一次事件里的按下指针整理成 [FingerSample]。
 *
 * 分区规则：
 * - 按下位置落在底部点击区（左半 / 右半）→ 计入 [FingerSample.buttonHeld]，
 *   **不**参与质心 / 间距 / 旋转计算（它是「被按住的鼠标键」，不是「在触控区滑动的手指」）；
 * - 其余按下指针 → 触控区手指，参与全部几何计算。
 */
private fun buildFingerSample(
    pressed: List<PointerInputChange>,
    zones: ClickZones?,
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
