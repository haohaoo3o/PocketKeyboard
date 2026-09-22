package com.pocketkeyboard.app.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
 * @param onIntent 凑指窗口内观察到 [GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT]
 *    根以上手指、意图明确但还没凑满 5 指时触发。参数是**最大**手指数（单调不减）。
 *    竖屏融合页用它主动收起系统输入法——键盘弹出后屏幕下半是 IME 窗口，
 *    落在 IME 上的手指到不了本层，5 指永远凑不齐（真机实测的主因），
 *    必须先让输入法让位，剩余的手指才落得进来。
 */
class PocketGestureHandler(
    val onPinch: () -> Unit = {},
    val onSpread: () -> Unit = {},
    val onSwipe: (SwipeDirection) -> Unit = {},
    val onIntent: (fingerCount: Int) -> Unit = {},
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
 * - **触控板层**：见 [GestureArbiter] 与 [awaitGestureOwner]。本层在滑动凑指窗口内凑满
 *   5 指就 `claimFiveFinger()` 接管；明确不是五指挥势时（窗口到期 / 手指明显在动 /
 *   全部抬起）立刻 `releaseToTrackpad()`，触控板层的 1–4 指手势随即开始，
 *   单指操作几乎无延迟。
 *
 * ## 与系统输入法的联动（真机实测主因，必读）
 *
 * 竖屏融合页唤起系统键盘后，屏幕下半被 **IME 窗口**占着：落在它上面的触摸直接进输入法，
 * App 的手势层根本看不到那几根手指，于是「5 指跨屏捏合」永远凑不齐。因此本层在凑指
 * 窗口内一看到 [GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT] 根手指，就通过
 * [PocketGestureHandler.onIntent] 通知页面**主动收起系统键盘**，把整块屏幕抢回来；
 * 剩余的手指随后落下，5 指才凑得齐。判定分支全部打在 logcat（tag `PocketGesture`），
 * 真机排查命令：`adb logcat -s PocketGesture`。
 *
 * @param handler 手势业务回调
 * @param arbiter 与触控板层共享的仲裁器；同一个实例必须同时传给 `Modifier.pocketGestures`
 *    和触控板层的 `pointerInput`，否则仲裁无效
 * @param gate 五指挥图意图闸门（见 [FiveFingerGate]）。传了它，页面 UI 层就能读到
 *    「是否该让位给五指挥势」；不传则只影响 UI 联动，不影响手势识别本身
 * @param enabled false 时整个手势层不挂载（调试用）
 */
@Composable
fun Modifier.pocketGestures(
    handler: PocketGestureHandler,
    arbiter: GestureArbiter = remember { GestureArbiter() },
    gate: FiveFingerGate? = null,
    enabled: Boolean = true,
): Modifier {
    // rememberUpdatedState：pointerInput 的 key 只有 arbiter，handler 变化时不会重启手势协程，
    // 但回调始终拿到最新实例（MainActivity 里 handler 依赖 mode / 文案，每次重组都会变）。
    val currentHandler by rememberUpdatedState(handler)
    // gate 同理：MainActivity 的 hander 每次重组都新建，闸门实例本身不变
    val currentGate by rememberUpdatedState(gate)
    return this.then(
        if (enabled) {
            Modifier.pointerInput(arbiter) {
                detectPocketGestures({ currentHandler }, arbiter, { currentGate })
            }
        } else {
            Modifier
        },
    )
}

/**
 * 一次触摸序列的「强制复位」策略（纯函数，JVM 单测覆盖）：所有指针抬起的那一个
 * 事件上，把闸门与仲裁器收敛到可安全进入下一次触摸的终态。
 *
 * ## 为什么不能一律 `arbiter.reset()`
 *
 * 触控板层用 `StateFlow.first { ... }` 观察归属：`releaseToTrackpad()` 刚写下的
 * `TRACKPAD` 如果在**同一批**代码里又被 `reset()` 清回 `UNDECIDED`，中间值可能被
 * StateFlow 的 conflation 吞掉——触控板层永远等不到裁决，只能熬满安全超时（真机
 * 症状 2 的卡死来源之一）。因此终态按当前归属分流：
 *
 * - `FIVE_FINGER` → `reset()`：触控板层在 claim 的那一刻就已经拿到过 `FIVE_FINGER`
 *   （claim 与结束至少隔一个事件），清掉只是防陈旧声明带进下一轮；
 * - `UNDECIDED` → `releaseToTrackpad()`：五指层没来得及表态序列就结束了（异常退出 /
 *   手势协程被 `resetPointerInputHandler` 取消）。交还给触控板层，解挂还在
 *   `awaitGestureOwner` 里等裁决的等待者，免于 1.5s 超时兜底；
 * - `TRACKPAD` → 保持不动：正常的放手裁决，触控板层可能还没观察到；下一轮
 *   `GestureArbiter.onGestureStart()` 会把它清回未裁定。
 *
 * 闸门（[FiveFingerGate]）没有「等待被观察方」，任何时候都在序列末尾立刻清零：
 * 同一事件稍后的 Main pass 上，输入区 `clickable` / 按键层就会读它（症状 3a 的门控）。
 *
 * 调用点有两处：五指层每轮手势的 `finally`（含协程被取消的路径）与独立的
 * [Modifier.gestureStateSelfHeal] 观察层（兜底）。
 */
internal fun endOfTouchSequence(arbiter: GestureArbiter, gate: FiveFingerGate?) {
    gate?.reset()
    when (arbiter.owner.value) {
        GestureOwner.FIVE_FINGER -> arbiter.reset()
        GestureOwner.UNDECIDED -> arbiter.releaseToTrackpad()
        GestureOwner.TRACKPAD -> Unit
    }
}

/**
 * 「触摸序列结束即强制复位」自愈层（问题 3a / 2 的兜底防线）。
 *
 * 挂在与 [pocketGestures] 同一个页面容器 Box 上，独立协程、零业务状态：只盯
 * 「本次触摸序列所有指针抬起」的那一个事件（含 Compose 对 `ACTION_CANCEL` 合成的
 * 全抬起事件），命中即调用 [endOfTouchSequence] 强制收敛闸门与仲裁器。
 *
 * 为什么必须有独立的一层：五指层自身的复位写在它的手势协程里，而该协程可能在
 * 复位之前就被取消（`SuspendingPointerInputModifierNodeImpl.resetPointerInputHandler`：
 * 密度 / 视图配置变化、`AndroidComposeView.coroutineContext` 更换都会走到）——
 * 协程一死，`FiveFingerGate.pendingFingers / claimed` 就永久停在让位态，输入区
 * `clickable` 被门控永久拦截（真机「系统键盘完全打不开」的卡死路径）。本层的协程
 * 没有任何超时 / 状态机，不持有跨序列状态，被重置后也能干净重启，因此可以在
 * 主手势层失能时接管复位。
 */
@Composable
fun Modifier.gestureStateSelfHeal(
    arbiter: GestureArbiter,
    gate: FiveFingerGate,
): Modifier = this.then(
    Modifier.pointerInput(arbiter, gate) {
        awaitEachGesture {
            // 等到「本次序列全部指针抬起」的事件：awaitEachGesture 每轮从序列起点开始，
            // 本循环读 Initial pass（父层最先看到），抬起后当前 currentEvent 即全空，
            // 外层框架的 awaitAllPointersUp 检查到已全抬起会直接进入下一轮、不会挂起
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) break
            }
            endOfTouchSequence(arbiter, gate)
            PocketGestureLog.trace("触摸序列结束 → 强制复位闸门/仲裁（自愈层）")
        }
    },
)


/**
 * 五指挥势识别主循环。
 *
 * 结构（外层 `awaitEachGesture` 保证每轮都从「所有手指抬起」的干净状态开始）：
 *
 * ```
 * 等第一根手指落下
 *   └─ 凑指窗口（滑动）内凑满 5 指？
 *        ├─ 否 → 仲裁器交给触控板层；等所有手指抬起；reset；进入下一轮
 *        └─ 是 → 仲裁器声明五指层接管
 *                └─ 记录初始几何（质心 + 指尖平均间距）
 *                   └─ 持续跟踪：收缩 / 张开 / 质心横移 超过阈值即触发并 consume
 *                   └─ 有手指抬起 → 手势结束
 *                └─ 等所有手指抬起；reset；进入下一轮
 * ```
 *
 * ## 凑指窗口为什么是「滑动的」
 *
 * 人手放下 5 根手指必然有先后：熟练的人 100ms 内放下，认真摆位的要 300ms 以上。窗口写成
 * 固定的 60ms 时，**人类几乎不可能凑齐**，五指挥势层永远等不到第 5 根手指、每次都直接放手，
 * 于是 pinch / spread / swipe 永远不触发（实测 bug）。因此改成：
 *
 * - 每有新手指落下 → 截止时间续期到「该手指落下 + [GestureConstants.FINGER_GATHER_RENEW_MS]」；
 * - 总截止不晚于「第一根手指落下 + [GestureConstants.FINGER_GATHER_MAX_MS]」；
 * - 已按下的手指位移超过 [GestureConstants.FINGER_GATHER_MOVE_SLOP] 且没有新手指继续落下
 *   → **立刻**放手（这是触控板手势正在进行，不是摆指）。见下一小节：这条只对 1–2 指生效；
 * - 所有手指都抬起 → **立刻**放手（手势在窗口内就结束了）。
 *
 * 于是五指手势有充足的凑指时间，而单指 / 双指触控板操作几乎感觉不到等待：
 * 「按下即拖动」的手指一动过阈值，五指层在同一批事件里就放手了。
 *
 * ## 位移放手只适用于 1–2 指（真机实测第三轮，张开 / 收缩不灵的主根因）
 *
 * 「位移超过 [GestureConstants.FINGER_GATHER_MOVE_SLOP] 就放手」对**张开 / 捏合**是错杀：
 * 用户做一次张开，手指本来就是**边落边张开**的——第 3、第 4 根落下的同时，先落下那几根正在
 * 向外扩，位移分分钟超过 slop。一旦 ≥3 指就据此放手，症状就是「触控板页做张开 / 收缩没反应」
 * （手指一分开，五指层已经把触摸交还给触控板层，跟踪阶段根本进不去）。
 *
 * 3 指即「有五指挥势意图」（[GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT]），
 * 意图已确立，于是规则改为：
 *
 * - **按下指数 ≤ 2** + 没有新手指落下 + 已按下手指位移超阈值 → 立刻放手。1–2 指是明确的
 *   单指 / 双指触控板手势（拖动 / 滚动 / 缩放），这条规则保证它们零延迟；
 * - **≥ 3 指**（意图已确立）→ 不再因位移放手，只受**总窗口上限**
 *   [GestureConstants.FINGER_GATHER_MAX_MS] 约束（到期仍不足 5 指才放手）。
 *
 * 判定逻辑抽在纯函数 [FingerGatherState] 里（JVM 单测可逐条跑），本函数只负责
 * 「等事件 + 掐时间 + 把结论翻译成回调」。
 *
 * ## 为什么真机上还是不灵（第二轮实测后补记）
 *
 * 上面这条时长链路已经修好了，真机依然不触发，剩下三个原因按影响排序：
 *
 * 1. **系统输入法遮挡**（主因）：竖屏融合页唤起键盘后，屏幕下半是 IME 窗口，落在它上面
 *    的手指到不了本层。因此本层在凑指窗口内一观察到
 *    [GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT] 根手指就通过
 *    [PocketGestureHandler.onIntent] 让页面收起输入法，把屏幕抢回来再继续凑。
 * 2. **掌心 / 掌根多报一根指针**：`fiveFingerGeometry` 曾要求「恰好 5 根」，多一根掌根
 *    接触就返回 null、整个手势被静默丢弃。现已改为「至少 5 根」。
 * 3. **横滑阈值 200dp**：440dpi 的 1080px 宽屏上折 550px，质心要横移过半屏。
 *    已降到 [GestureConstants.SWIPE_DISTANCE]（80dp / 220px）。
 */
private suspend fun PointerInputScope.detectPocketGestures(
    handler: () -> PocketGestureHandler,
    arbiter: GestureArbiter,
    gate: () -> FiveFingerGate?,
) {
    val swipeThresholdPx = GestureConstants.SWIPE_DISTANCE.toPx()
    val gatherSlopPx = GestureConstants.FINGER_GATHER_MOVE_SLOP.toPx()
    awaitEachGesture {
        // finally 是复位路径审计（问题 3a / 2）后的关键改动：正常路径在 awaitAllPointersUp
        // 之后收敛，而协程被取消（ACTION_CANCEL 之外的 handler 重置——密度 / 视图配置
        // 变化、AndroidComposeView.coroutineContext 更换）时原代码**任何复位都到不了**，
        // 闸门 / 仲裁器永久停在让位态。finally 里的 endOfTouchSequence 非挂起、可安全
        // 在取消路径上执行；正常路径它是幂等的第二道保险
        try {
            // 读 Initial pass：父节点先于子节点看到事件，抢在按键 / 触控板之前表态
            val firstDown = awaitFirstDownOnPass()

            val gathered = awaitFiveFingersGathered(firstDown, gatherSlopPx, handler, gate)
            if (gathered == null) {
                // 明确不是五指挥势：立刻释放，触控板层的 awaitGestureOwner 随即返回 TRACKPAD
                arbiter.releaseToTrackpad()
                awaitAllPointersUp()
                return@awaitEachGesture
            }

            // 抢到归属：触控板层的 awaitGestureOwner 会立刻返回 FIVE_FINGER 并放弃本次手势
            arbiter.claimFiveFinger()
            gate()?.observeGather(fingerCount = gathered.pressedCount, claimed = true)
            PocketGestureLog.decision(
                "凑满 ${gathered.pressedCount} 指 → 接管手势，" +
                    "横滑阈值 ${"%.0f".format(swipeThresholdPx)}px",
            )

            val initialPoints = gathered.event.changes.filter { it.pressed }.toFingerPoints()
            val initial = fiveFingerGeometry(initialPoints)
            if (initial == null) {
                // 理论上不会发生（上面已确认 ≥5 指），防御性处理
                PocketGestureLog.warn("凑满 ${gathered.pressedCount} 指却算不出几何，放弃本轮")
                awaitAllPointersUp()
                return@awaitEachGesture
            }

            PocketGestureLog.trace(
                "初始几何 质心(${"%.0f".format(initial.centroidX)}, " +
                    "${"%.0f".format(initial.centroidY)}) 平均间距 ${"%.0f".format(initial.meanSpacing)}px",
            )

            trackAndFireFiveFingerGesture(initial, swipeThresholdPx, handler)

            awaitAllPointersUp()
        } finally {
            endOfTouchSequence(arbiter, gate())
        }
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
    // 上一次打日志时的几何，用于把「本次事件比上次移动了多少」打进日志
    var lastLogged = initial

    while (deviceSwitches <= GestureConstants.MAX_DEVICE_SWITCHES_PER_GESTURE) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val pressed = event.changes.filter { it.pressed }
        if (pressed.size < GestureConstants.REQUIRED_FINGER_COUNT) {
            // 有手指抬起，五指几何不再成立，手势自然结束
            PocketGestureLog.decision("跟踪中有手指抬起（剩 ${pressed.size} 指）→ 手势结束")
            return
        }
        val current = fiveFingerGeometry(pressed.toFingerPoints()) ?: return
        // 质心基准平移到上一次横滑的位置，间距基准仍是初始值
        val baseline = initial.copy(centroidX = lastSwipeCentroidX)
        when (val verdict = classifyFiveFingerGesture(baseline, current, swipeThresholdPx)) {
            GestureVerdict.None -> {
                PocketGestureLog.trace(
                    "跟踪 质心x ${"%.0f".format(current.centroidX)} " +
                        "(较上次 ${"%+.0f".format(current.centroidX - lastLogged.centroidX)}px)，" +
                        "间距 ${"%.0f".format(current.meanSpacing)}px " +
                        "(初始 ${"%.0f".format(initial.meanSpacing)}px) → 未达阈值",
                )
            }

            GestureVerdict.Pinch, GestureVerdict.Spread -> {
                // 只有真正识别出五指挥势的这一下才消费，告诉其他层「这次触摸有主了」
                event.changes.forEach { it.consume() }
                val label = if (verdict == GestureVerdict.Pinch) "收缩" else "张开"
                PocketGestureLog.decision(
                    "判定[$label] 间距 ${"%.0f".format(initial.meanSpacing)}px → " +
                        "${"%.0f".format(current.meanSpacing)}px " +
                        "(比例 ${"%.2f".format(current.meanSpacing / initial.meanSpacing)}) → 触发回调",
                )
                when (verdict) {
                    GestureVerdict.Pinch -> handler().onPinch()
                    else -> handler().onSpread()
                }
                return
            }

            is GestureVerdict.Swipe -> {
                if (deviceSwitches >= GestureConstants.MAX_DEVICE_SWITCHES_PER_GESTURE) return
                event.changes.forEach { it.consume() }
                PocketGestureLog.decision(
                    "判定[横滑 ${verdict.direction}] 质心x ${"%.0f".format(lastSwipeCentroidX)} → " +
                        "${"%.0f".format(current.centroidX)} " +
                        "(阈值 ${"%.0f".format(swipeThresholdPx)}px) → 触发回调",
                )
                handler().onSwipe(verdict.direction)
                deviceSwitches++
                lastSwipeCentroidX = current.centroidX
            }
        }
        lastLogged = current
    }
}


/**
 * 第一根手指落下时的信息：down 本身 + 它所在的那个 [PointerEvent]。
 *
 * 为什么连事件一起返回：一次 `dispatchTouchEvent` 可能把多根手指的 down 打包进同一个
 * [PointerEvent]（合成注入、系统批量上报都会这样）。只拿 change 不拿事件的话，凑指循环会
 * 去读**下一个**事件，万一中间没有任何移动事件就直接错过「5 指同帧落下」这种最快的情况。
 */
private class FirstDownOnPass(
    val change: PointerInputChange,
    val event: PointerEvent,
)

/**
 * 凑满五指那一刻的快照。
 *
 * 为什么连 [PointerEvent] 一起带出来：后面要按这些指针的**当前位置**算初始几何，
 * 而 `pressedCount` 单独存一份是给日志用的——真机上「掌心多报一根指针」会让它大于 5，
 * 这正是排查「明明 5 指按下却不触发」时要看的关键数字。
 */
private class GatheredFingers(
    val event: PointerEvent,
    val pressedCount: Int,
)

/**
 * 凑指窗口观察到的一根手指（纯数据，由 `PointerInputChange` 映射而来）。
 *
 * 用 `pointerId: Long` 而不是 Compose 的 `PointerId`：判定状态机是纯 Kotlin，
 * 单测里直接造即可，不需要 Compose 的指针体系。
 */
internal data class GatherFinger(
    val pointerId: Long,
    val x: Float,
    val y: Float,
    val uptimeMillis: Long,
)

/** 凑指窗口放手的原因（写进 logcat 结论行，真机排查用）。 */
internal enum class GatherReleaseReason {
    /** 窗口内所有手指都抬起了。 */
    ALL_FINGERS_UP,

    /** 1–2 指按下、没有新手指落下且已按下手指位移超阈值（触控板手势正在进行）。 */
    FINGERS_MOVING,

    /** 窗口到期（续期 / 总上限）仍不足 5 指。 */
    WINDOW_EXPIRED,
}

/** 凑指窗口对一次观察给出的结论。 */
internal sealed interface GatherVerdict {

    /** 凑满 [GestureConstants.REQUIRED_FINGER_COUNT] 指：进入跟踪阶段。 */
    data class Gathered(val fingerCount: Int) : GatherVerdict

    /**
     * 继续等：下一个事件 / [deadlineUptime] 到期前还没凑满就放弃。
     *
     * @param deadlineUptime 本轮的截止时刻（MotionEvent uptime 体系，ms）
     * @param intentEstablished 是否已达到「五指挥势意图」手指数（≥3 指）——
     *    调用方据此收起系统输入法、让按键层停止激活
     * @param newFingerArrived 本次观察里是否有新手指落下（续期 + 日志用）
     * @param fingerCount 当前按下指数
     */
    data class Waiting(
        val deadlineUptime: Long,
        val intentEstablished: Boolean,
        val newFingerArrived: Boolean,
        val fingerCount: Int,
    ) : GatherVerdict

    /** 明确不是五指挥势：交还给触控板层。 */
    data class Release(val reason: GatherReleaseReason) : GatherVerdict
}

/**
 * 凑指窗口的判定状态机（纯 Kotlin，JVM 单测可逐条跑）。
 *
 * 规则详见 [detectPocketGestures] 的「凑指窗口为什么是滑动的 / 位移放手只适用于 1–2 指」
 * 两节。两条关键取舍：
 *
 * 1. **位移基准每来一根新手指就整体重设**：慢慢把 5 根手指摆开时，先落下的那几根有轻微
 *    漂移是正常摆位，不算「拖动」；
 * 2. **≥ [GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT] 指后不再因位移放手**：
 *    张开 / 捏合本来就是「边落边扩」，3 指起只受总窗口上限约束。
 *
 * 状态机不读系统时钟：时间由调用方以 `nowUptime`（事件 uptime）喂进来，因此测试里
 * 可以用任意时间序列驱动。
 *
 * @param firstDownUptime 第一根手指落下的时刻
 * @param firstFrame 第一根手指所在事件里已按下的手指（同一帧可能打包了多根 down）
 * @param moveSlopPx 「明显在动」的位移阈值（px）
 * @param renewMs 每根新手指的续期步长
 * @param maxMs 总窗口上限（自第一根手指起算）
 * @param requiredFingerCount 凑满几指算五指挥势
 * @param intentFingerCount 几指起算「有五指挥势意图」
 */
internal class FingerGatherState(
    firstDownUptime: Long,
    firstFrame: List<GatherFinger>,
    private val moveSlopPx: Float,
    private val renewMs: Long = GestureConstants.FINGER_GATHER_RENEW_MS,
    private val maxMs: Long = GestureConstants.FINGER_GATHER_MAX_MS,
    private val requiredFingerCount: Int = GestureConstants.REQUIRED_FINGER_COUNT,
    private val intentFingerCount: Int = GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT,
) {
    private val hardDeadline: Long = firstDownUptime + maxMs

    /** 位移基准：[pointerId] → 落下位置。每来一根新手指就整体重设。 */
    private var baseline: Map<Long, Offset> =
        firstFrame.associate { it.pointerId to Offset(it.x, it.y) }

    /** 最近一根新手指落下的时刻（续期基准）。 */
    private var lastNewFingerUptime: Long = firstDownUptime

    /** 是否已达到「五指挥势意图」手指数（一旦确立不再撤销，直到一次手势结束复位）。 */
    var intentEstablished: Boolean = false
        private set

    /**
     * 喂入一次观察。
     *
     * @param pressed 当前按下的手指（位置是**当前**位置）
     * @param nowUptime 本次观察对应的时刻（本批事件里的最新 uptime）
     */
    fun observe(pressed: List<GatherFinger>, nowUptime: Long): GatherVerdict {
        if (pressed.isEmpty()) {
            // 手势在窗口内就结束了：立刻放手，否则触控板层等不到裁决、这次轻点会丢
            return GatherVerdict.Release(GatherReleaseReason.ALL_FINGERS_UP)
        }
        if (pressed.size >= requiredFingerCount) {
            return GatherVerdict.Gathered(pressed.size)
        }

        val newFingerArrived = pressed.any { !baseline.containsKey(it.pointerId) }
        if (pressed.size >= intentFingerCount) intentEstablished = true

        if (newFingerArrived) {
            // 有新手指落下：续期 + 重设位移基准
            baseline = pressed.associate { it.pointerId to Offset(it.x, it.y) }
            lastNewFingerUptime = nowUptime
        } else if (!intentEstablished && fingersMovedBeyondSlop(pressed)) {
            // 没有新手指、而已按下的手指明显在动 → 这是正在进行的触控板手势。
            // 仅 1–2 指：≥3 指时意图已确立（张开 / 捏合就是边落边扩），不再据此放手
            return GatherVerdict.Release(GatherReleaseReason.FINGERS_MOVING)
        }

        // 意图已确立后只受总窗口上限约束（续期窗口不再宽限）；否则取两者较早者
        val deadline = if (intentEstablished) {
            hardDeadline
        } else {
            minOf(lastNewFingerUptime + renewMs, hardDeadline)
        }
        if (nowUptime >= deadline) {
            return GatherVerdict.Release(GatherReleaseReason.WINDOW_EXPIRED)
        }
        return GatherVerdict.Waiting(
            deadlineUptime = deadline,
            intentEstablished = intentEstablished,
            newFingerArrived = newFingerArrived,
            fingerCount = pressed.size,
        )
    }

    /** 已按下的手指里，有没有哪根相对基准位置的位移超过 [moveSlopPx]。 */
    private fun fingersMovedBeyondSlop(pressed: List<GatherFinger>): Boolean =
        pressed.any { finger ->
            val from = baseline[finger.pointerId] ?: return@any false
            fingerTravel(Offset(finger.x, finger.y), from) > moveSlopPx
        }
}

/**
 * 在滑动凑指窗口内等待凑满 [GestureConstants.REQUIRED_FINGER_COUNT] 指。
 *
 * 窗口规则（详见 [detectPocketGestures] 的说明）：
 * - 每有新手指落下 → 截止时间续期到「该手指落下 + [GestureConstants.FINGER_GATHER_RENEW_MS]」；
 * - 总截止不晚于「第一根手指落下 + [GestureConstants.FINGER_GATHER_MAX_MS]」；
 * - **1–2 指**按下、没有新手指、而已按下手指位移超过 [gatherSlopPx] → 立刻返回 null
 *   （触控板手势正在进行）；≥3 指（意图已确立）不再因位移放手；
 * - 所有手指都抬起 → 立刻返回 null（手势在窗口内就结束，也必须让触控板层收到裁决）。
 *
 * 判定全部在 [FingerGatherState] 里（纯函数，单测覆盖）；本函数只负责等事件、掐时间，
 * 以及把「意图已确立」翻译成 [PocketGestureHandler.onIntent] / [FiveFingerGate.observeGather]。
 *
 * ## 与系统输入法的联动
 *
 * 一观察到五指挥势意图（≥ [GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT] 指），就通过
 * [handler] 的 [PocketGestureHandler.onIntent] 把当前手指数报出去（同时写进 [gate]）。
 * 竖屏融合页据此**主动收起系统输入法**：否则落在 IME 窗口上的手指到不了本层，
 * 5 指永远凑不齐（真机实测的主因）。
 *
 * @return 凑满那一刻的 [PointerEvent]；明确凑不齐则返回 null。
 */
private suspend fun AwaitPointerEventScope.awaitFiveFingersGathered(
    firstDown: FirstDownOnPass,
    gatherSlopPx: Float,
    handler: () -> PocketGestureHandler,
    gate: () -> FiveFingerGate?,
): GatheredFingers? {
    var event = firstDown.event
    val state = FingerGatherState(
        firstDownUptime = firstDown.change.uptimeMillis,
        firstFrame = event.changes.filter { it.pressed }.map { it.toGatherFinger() },
        moveSlopPx = gatherSlopPx,
    )
    var intentReported = false

    while (true) {
        val pressed = event.changes.filter { it.pressed }
        val latestUptime = pressed.maxOfOrNull { it.uptimeMillis }
            ?: firstDown.change.uptimeMillis
        val verdict = state.observe(
            pressed = pressed.map { it.toGatherFinger() },
            nowUptime = latestUptime,
        )
        when (verdict) {
            is GatherVerdict.Gathered ->
                return GatheredFingers(event = event, pressedCount = verdict.fingerCount)

            is GatherVerdict.Release -> {
                when (verdict.reason) {
                    GatherReleaseReason.ALL_FINGERS_UP ->
                        PocketGestureLog.decision("凑指窗口内所有手指抬起 → 放手给触控板层")
                    GatherReleaseReason.FINGERS_MOVING ->
                        PocketGestureLog.decision(
                            "${pressed.size} 指按下且有手指位移超过 " +
                                "${"%.0f".format(gatherSlopPx)}px（没有新手指落下，≤2 指）" +
                                " → 放手给触控板层",
                        )
                    GatherReleaseReason.WINDOW_EXPIRED ->
                        PocketGestureLog.decision(
                            "凑指窗口到期（仅 ${pressed.size} 指，续期 " +
                                "${GestureConstants.FINGER_GATHER_RENEW_MS}ms / " +
                                "上限 ${GestureConstants.FINGER_GATHER_MAX_MS}ms）→ 放手给触控板层",
                        )
                }
                return null
            }

            is GatherVerdict.Waiting -> {
                if (verdict.intentEstablished && !intentReported) {
                    // 意图明确：让页面收起系统输入法、按键层停止激活，把屏幕让给五指手势。
                    // 只报一次（onIntent 是「意图出现」事件，不需要每个事件重发）
                    intentReported = true
                    gate()?.observeGather(fingerCount = verdict.fingerCount, claimed = false)
                    handler().onIntent(verdict.fingerCount)
                }
                // 日志只打「新手指落下」这一行：移动事件也打会把 logcat 刷爆，
                // 而排查「为什么凑不齐」只需要知道每根手指的落下时刻
                if (verdict.newFingerArrived) {
                    PocketGestureLog.trace(
                        "第 ${verdict.fingerCount} 指落下（本轮窗口剩 " +
                            "${verdict.deadlineUptime - latestUptime}ms）",
                    )
                }
                val remaining = verdict.deadlineUptime - latestUptime
                // remaining <= 0 时 observe 已经给出 WINDOW_EXPIRED，这里是防御
                val next = if (remaining <= 0L) {
                    null
                } else {
                    withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Initial) }
                }
                if (next == null) {
                    PocketGestureLog.decision(
                        "凑指窗口等待超时（剩 ${remaining}ms 无新事件）→ 放手给触控板层",
                    )
                    return null
                }
                event = next
            }
        }
    }
}

/** `PointerInputChange` → 凑指判定的纯数据表示。 */
private fun PointerInputChange.toGatherFinger(): GatherFinger = GatherFinger(
    pointerId = id.value,
    x = position.x,
    y = position.y,
    uptimeMillis = uptimeMillis,
)

/** 一根手指从基准位置 [from] 走到 [to] 的直线距离（px）。 */
private fun fingerTravel(to: Offset, from: Offset): Float =
    kotlin.math.hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble()).toFloat()

/**
 * 等待所有手指抬起（`awaitEachGesture` 尾部也会做，这里显式写出来以便复位仲裁器）。
 *
 * ## 必须先查当前事件（问题 3a / 2 的根因，真机实测）
 *
 * 修复前这里是「无条件等下一个事件」：当**同一个事件**里所有指针都已抬起（同帧抬指、
 * 或 Compose 对系统 `ACTION_CANCEL` 合成的全抬起事件——见框架
 * `SuspendingPointerInputFilter.onCancelPointerInput`）时，本函数不会返回，而是挂到
 * **下一次触摸结束**才继续执行后面的复位。后果就是两个真机症状：
 *
 * - `FiveFingerGate` 的 `pendingFingers ≥ 3 / claimed` 陈旧覆盖下一次触摸 →
 *   输入区 `clickable` 门控拦截点击、`LaunchedEffect` 压制自动弹出（症状 3a）；
 * - 五指层卡在上一轮收尾、不跑下一轮凑指 → 不会 `releaseToTrackpad()` → 触控板层
 *   每次都熬满 1500ms 安全超时（症状 2：连续手势交替失灵）。
 *
 * 框架自带的 `androidx.compose.foundation.gestures.awaitAllPointersUp` 语义正是
 * 「先查 `currentEvent` 已全抬起则立即返回，否则再等」——此处与它对齐
 * （回归测试见 `PocketGestureSequenceResetTest`）。
 */
private suspend fun AwaitPointerEventScope.awaitAllPointersUp() {
    if (currentEvent.changes.none { it.pressed }) return
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
 *
 * @return 落下的那根手指 + 它所在的事件（事件里可能还打包了同一帧落下的其它手指，
 *     见 [FirstDownOnPass] 的说明）。
 */
private suspend fun AwaitPointerEventScope.awaitFirstDownOnPass(
    pass: PointerEventPass = PointerEventPass.Initial,
): FirstDownOnPass {
    while (true) {
        val event = awaitPointerEvent(pass)
        event.changes.forEach { change ->
            if (change.pressed && !change.previousPressed) {
                return FirstDownOnPass(change, event)
            }
        }
    }
}
