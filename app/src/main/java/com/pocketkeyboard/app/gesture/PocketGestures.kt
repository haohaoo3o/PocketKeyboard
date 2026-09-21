package com.pocketkeyboard.app.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 五指挥势的业务回调。
 *
 * 三个回调都在手势识别成功的那一个事件里同步调用（不切线程），可以直接写
 * `MainViewModel.setMode(...)` / `setActiveDevice(...)`。
 * 全部参数都有默认空实现，用的时候只覆盖需要的那个即可。
 *
 * @param onPinch 五指收缩（指尖平均间距缩小超过 [GestureConstants.PINCH_SHRINK_RATIO]）
 * @param onSpread 五指张开（间距放大超过 [GestureConstants.SPREAD_GROW_RATIO]）
 * @param onSwipe 五指整体横滑（质心水平位移超过 [GestureConstants.SWIPE_DISTANCE]），
 *    [SwipeDirection.RIGHT] 表示手指整体向右移动
 */
class PocketGestureHandler(
    val onPinch: () -> Unit = {},
    val onSpread: () -> Unit = {},
    val onSwipe: (SwipeDirection) -> Unit = {},
)

/**
 * 给页面容器挂上「五指挥势识别层」。
 *
 * ## 挂载位置
 *
 * 必须挂在**页面容器的最底层**，也就是承载 `AnimatedContent` 的那个 Box 上：
 *
 * ```kotlin
 * Box(
 *     modifier = Modifier
 *         .fillMaxSize()
 *         .pocketGestures(handler, arbiter)   // ← 最底层手势层
 * ) {
 *     AnimatedContent(targetState = mode, ...) { /* 键盘 / 触控板 / 配对页 */ }
 *     GestureFeedback(hudState)               // HUD 与设备切换覆盖层画在最上层
 * }
 * ```
 *
 * 这样键盘层、触控板层都是它的**子节点**，可以按下面的规则与它共存。
 *
 * ## awaitPointerEventScope 的传递策略（重要，改代码前必读）
 *
 * Compose 的一次 `PointerEvent` 会沿 hit-test 路径按三个 pass 依次下发：
 *
 * | pass | 顺序 | 用途 |
 * | --- | --- | --- |
 * | `PointerEventPass.Initial` | 根 → 叶 | 父节点抢先看到事件、抢先消费；父在这里 `consume()` 后，子节点用 `requireUnconsumed = true` 就再也看不到该指针 |
 * | `PointerEventPass.Main` | 叶 → 根 | 子节点优先消费；子消费掉的事件父节点仍会收到，但 `PointerInputChange.isConsumed == true` |
 * | `PointerEventPass.Final` | 根 → 叶 | 父节点兜底清理 |
 *
 * 本手势层**读 Initial pass**（`awaitPointerEvent(PointerEventPass.Initial)`），理由：
 *
 * 1. 五指挥势必须和触控板层抢「这次触摸归谁」，而 Initial pass 是父节点唯一能早于子节点
 *    看到事件的地方。只有先看到第 5 根手指，才能在触控板层动手之前调用
 *    [GestureArbiter.claimFiveFinger]；
 * 2. 按键层的规则是「≥5 指针时不消费、放行给底层手势层」，所以本层在 Initial pass 里看到
 *    的事件一定是干净的，可以直接拿 5 根手指的位置做几何计算；
 * 3. 本层**只在真正识别出五指挥势的那一个事件上 `consume()`**，单指打字全程不消费，
 *    因此不会影响按键层（按键在 Main pass 里仍然第一个看到单指事件）。
 *
 * 入口不用框架的 `awaitFirstDown`（它只提供 Main pass），而是自己解析
 * `change.pressed && !change.previousPressed`，见文件末尾的 [awaitFirstDownOnPass]。
 *
 * ## 与其他层的共存规则
 *
 * - **键盘按键层**：见 [pocketKeyGestures]，按键的 pointerInput 只响应单指，
 *   检测到按下指针数 ≥ [GestureConstants.REQUIRED_FINGER_COUNT] 时不消费、直接放行。
 * - **触控板层**：见 [GestureArbiter] 与 [awaitGestureOwner]，按下后
 *   [GestureConstants.ARBITRATION_WINDOW_MS] 内凑满 5 指归本层，否则归触控板的 1–4 指手势。
 *
 * @param handler 手势业务回调
 * @param arbiter 与触控板层共享的仲裁器；同一个实例必须同时传给 `Modifier.pocketGestures`
 *   和触控板层的 `pointerInput`，否则仲裁无效
 * @param enabled false 时整个手势层不挂载（调试用）
 */
@Composable
fun Modifier.pocketGestures(
    handler: PocketGestureHandler,
    arbiter: GestureArbiter = remember { GestureArbiter() },
    enabled: Boolean = true,
): Modifier {
    // rememberUpdatedState：pointerInput 的 key 只有 arbiter，handler 变化时不会重启手势协程，
    // 但回调始终拿到最新实例（MainActivity 里 handler 依赖 mode / 文案，每次重组都会变）。
    val currentHandler by rememberUpdatedState(handler)
    return this.then(
        if (enabled) {
            Modifier.pointerInput(arbiter) {
                detectPocketGestures({ currentHandler }, arbiter)
            }
        } else {
            Modifier
        },
    )
}

/**
 * 五指挥势识别主循环。
 *
 * 结构（外层 `awaitEachGesture` 保证每轮都从「所有手指抬起」的干净状态开始）：
 *
 * ```
 * 等第一根手指落下
 *   └─ 仲裁窗口内凑满 5 指？
 *        ├─ 否 → 仲裁器交给触控板层；等所有手指抬起；reset；进入下一轮
 *        └─ 是 → 仲裁器声明五指层接管
 *                └─ 记录初始几何（质心 + 指尖平均间距）
 *                   └─ 持续跟踪：收缩 / 张开 / 质心横移 超过阈值即触发并 consume
 *                   └─ 有手指抬起 → 手势结束
 *                └─ 等所有手指抬起；reset；进入下一轮
 * ```
 */
private suspend fun PointerInputScope.detectPocketGestures(
    handler: () -> PocketGestureHandler,
    arbiter: GestureArbiter,
) {
    val swipeThresholdPx = GestureConstants.SWIPE_DISTANCE.toPx()
    awaitEachGesture {
        // 读 Initial pass：父节点先于子节点看到事件，抢在按键 / 触控板之前表态
        val firstDown = awaitFirstDownOnPass()
        val gatherDeadline = firstDown.uptimeMillis + GestureConstants.FINGER_GATHER_WINDOW_MS

        val gathered = awaitFiveFingersGathered(firstDown.uptimeMillis, gatherDeadline)
        if (gathered == null) {
            // 仲裁窗口内没凑满 5 指：本次触摸归触控板层，本层不消费、不动作
            arbiter.releaseToTrackpad()
            awaitAllPointersUp()
            arbiter.reset()
            return@awaitEachGesture
        }

        // 抢到归属：触控板层的 awaitDecision() 会立刻返回 FIVE_FINGER 并放弃本次手势
        arbiter.claimFiveFinger()

        val initialPoints = gathered.changes.filter { it.pressed }.toFingerPoints()
        val initial = fiveFingerGeometry(initialPoints)
        if (initial == null) {
            // 理论上不会发生（上面已确认 5 指），防御性处理
            awaitAllPointersUp()
            arbiter.reset()
            return@awaitEachGesture
        }

        trackAndFireFiveFingerGesture(initial, swipeThresholdPx, handler)

        awaitAllPointersUp()
        arbiter.reset()
    }
}

/**
 * 跟踪阶段：比较「初始几何」与「当前几何」，命中阈值就触发回调。
 *
 * - 收缩 / 张开是「一次性」手势，命中即结束；
 * - 横滑按 [GestureConstants.MAX_DEVICE_SWITCHES_PER_GESTURE] 配额发放：每再移动一个
 *   [GestureConstants.SWIPE_DISTANCE] 才允许切下一台，避免一次长滑连续跳过多台设备。
 */
private suspend fun AwaitPointerEventScope.trackAndFireFiveFingerGesture(
    initial: FiveFingerGeometry,
    swipeThresholdPx: Float,
    handler: () -> PocketGestureHandler,
) {
    var deviceSwitches = 0
    // 上一次触发横滑时的质心横坐标；下一次横滑要以它为基准再移动一个阈值距离
    var lastSwipeCentroidX = initial.centroidX

    while (deviceSwitches <= GestureConstants.MAX_DEVICE_SWITCHES_PER_GESTURE) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val pressed = event.changes.filter { it.pressed }
        if (pressed.size < GestureConstants.REQUIRED_FINGER_COUNT) {
            // 有手指抬起，五指几何不再成立，手势自然结束
            return
        }
        val current = fiveFingerGeometry(pressed.toFingerPoints()) ?: return
        // 质心基准平移到上一次横滑的位置，间距基准仍是初始值
        val baseline = initial.copy(centroidX = lastSwipeCentroidX)
        when (val verdict = classifyFiveFingerGesture(baseline, current, swipeThresholdPx)) {
            GestureVerdict.None -> Unit
            GestureVerdict.Pinch, GestureVerdict.Spread -> {
                // 只有真正识别出五指挥势的这一下才消费，告诉其他层「这次触摸有主了」
                event.changes.forEach { it.consume() }
                when (verdict) {
                    GestureVerdict.Pinch -> handler().onPinch()
                    else -> handler().onSpread()
                }
                return
            }
            is GestureVerdict.Swipe -> {
                if (deviceSwitches >= GestureConstants.MAX_DEVICE_SWITCHES_PER_GESTURE) return
                event.changes.forEach { it.consume() }
                handler().onSwipe(verdict.direction)
                deviceSwitches++
                lastSwipeCentroidX = current.centroidX
            }
        }
    }
}

/**
 * 在仲裁窗口内等待凑满 [GestureConstants.REQUIRED_FINGER_COUNT] 指。
 *
 * @return 凑满那一刻的 [PointerEvent]；窗口耗尽仍未凑齐则返回 null。
 *   超时用的是**绝对截止时间**（首指按下时间 + 窗口时长），因此中间即使有大量移动事件
 *   也不会把窗口无限续期。
 */
private suspend fun AwaitPointerEventScope.awaitFiveFingersGathered(
    firstDownUptime: Long,
    deadline: Long,
): PointerEvent? {
    var latestUptime = firstDownUptime
    while (true) {
        val remaining = deadline - latestUptime
        if (remaining <= 0L) return null
        val event = withTimeoutOrNull(remaining) {
            awaitPointerEvent(PointerEventPass.Initial)
        } ?: return null
        latestUptime = event.changes.maxOfOrNull { it.uptimeMillis } ?: latestUptime
        if (event.changes.count { it.pressed } >= GestureConstants.REQUIRED_FINGER_COUNT) {
            return event
        }
    }
}

/** 等待所有手指抬起（`awaitEachGesture` 尾部也会做，这里显式写出来以便复位仲裁器）。 */
private suspend fun AwaitPointerEventScope.awaitAllPointersUp() {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.none { it.pressed }) return
    }
}

/**
 * 在指定 pass 上等待第一根手指落下。
 *
 * 不使用框架的 `awaitFirstDown`，因为它只提供 Main pass；本层要读 Initial pass，
 * 才能保证「父节点先于子节点看到第 5 根手指并抢到仲裁」。
 */
private suspend fun AwaitPointerEventScope.awaitFirstDownOnPass(
    pass: PointerEventPass = PointerEventPass.Initial,
): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent(pass)
        event.changes.forEach { change ->
            if (change.pressed && !change.previousPressed) return change
        }
    }
}
