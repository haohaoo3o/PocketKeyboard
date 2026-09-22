package com.pocketkeyboard.app.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 键盘按键层的指针回调。
 *
 * @param onPress 单指按下且该指针未被其他节点消费时触发（可以在这里发键盘报告）
 * @param onRelease 同一根手指正常抬起时触发（发「松开全部按键」报告）
 * @param onAbandoned 按下指针数达到 [GestureConstants.REQUIRED_FINGER_COUNT] 时触发：
 *    本次按下被判定为五指挥势，按键必须作废（补发一次松键，避免卡键）
 * @param onTouchDown 手指刚落下、还没过五指防误触门控时触发。只用于**视觉 / 触觉**
 *    反馈（键帽下陷、震动），不承载任何激活语义——真正「这个键生效了」始终看 [onPress]。
 *    没有它，门控期间键帽不会有下陷反馈，观感上就像按键失灵。
 */
class PocketKeyGestureHandler(
    val onPress: () -> Unit = {},
    val onRelease: () -> Unit = {},
    val onAbandoned: () -> Unit = {},
    val onTouchDown: () -> Unit = {},
)

/**
 * 键盘按键专用指针策略：**只响应单指，≥5 指针时不消费、放行给底层五指挥势层**。
 *
 * 每个按键（以及任何只应该响应单指的子层）都挂这个 modifier：
 *
 * ```kotlin
 * Box(
 *     modifier = Modifier
 *         .size(64.dp)
 *         .pocketKeyGestures(
 *             PocketKeyGestureHandler(
 *                 onPress = { transport.sendKeyboardReport(0, byteArrayOf(usage)) },
 *                 onRelease = { transport.sendKeyboardReport(0, byteArrayOf()) },
 *                 onAbandoned = { transport.sendKeyboardReport(0, byteArrayOf()) },
 *             ),
 *         ),
 * )
 * ```
 *
 * ## awaitPointerEventScope 的传递策略（必读）
 *
 * Compose 的一次 `PointerEvent` 沿 hit-test 路径按三个 pass 下发：
 *
 * ```
 * Initial：根 → 叶   父可以先消费；父消费后子用 requireUnconsumed = true 就看不到了
 * Main   ：叶 → 根   子先消费；子消费掉的事件父仍会收到但 isConsumed == true
 * Final  ：根 → 叶   父兜底
 * ```
 *
 * 页面结构上，五指挥势层（`Modifier.pocketGestures`）是按键的**父节点**，因此：
 *
 * - 单指按下时，按键在 **Main pass**（子先于父）第一时间拿到事件，`awaitFirstDown` 成功，
 *   立刻 `consume()` 该 change。父节点随后仍会收到同一个事件，但看到 `isConsumed == true`；
 *   本 modifier 的判定不依赖消费状态，所以不影响五指识别，同时其他按键也不会重复响应
 *   同一根手指（它们的 `awaitFirstDown(requireUnconsumed = true)` 会跳过已消费的指针）。
 * - 当按下指针数达到 [GestureConstants.REQUIRED_FINGER_COUNT]（5）时，按键**停止消费并
 *   直接放弃本轮手势**：不再 consume 任何 change，让父节点的五指挥势层在同一个 Main pass
 *   的后续环节完整拿到这 5 根手指的位置，从而识别收缩 / 张开 / 整体横滑。
 * - 因为父节点只在 Main pass 里读事件、且只用 `requireUnconsumed = false` 的入口，
 *   按键「放行」的动作不需要任何额外 API，只要自己不 consume 即可。
 *
 * 换句话说：**子层（按键）负责「单指时独占、≥5 指时让路」，父层（五指挥势）负责「看到 5 指
 * 就接管」**，两边靠 Main pass 的「叶 → 根」顺序和 `PointerInputChange.isConsumed` 协同，
 * 不需要互相持有引用。
 *
 * ## 五指挥势防误触：按下后先「按住不激活」（问题 3b）
 *
 * 上面的规则只保证「5 指凑齐后按键不再响应」，但按键在**第 1 根手指**落下时就激活了
 * （`onPress`）——用户做一次五指捏合，落在键帽上的那几颗键会先各打一次给被控设备，
 * 然后才被作废。这就是用户反馈的「五指挥势误触键盘」。
 *
 * 因此按下后先不激活，进入 [awaitKeyActivation] 的门控状态机：
 *
 * ```
 * 手指落下（consume，进入门控）
 *   ├─ 按下指针数达到 5        → 判定为五指挥势，onAbandoned，一次报告都不发
 *   ├─ 别的指针抬起（峰值下降）→ 不可能是五指伸展，立刻激活（和弦时序正确）
 *   ├─ 本指抬起且无其它指针     → 孤立轻点，立刻激活并松开（与改动前观感一致）
 *   ├─ 本指抬起但还有别人按着   → 等 KEY_TAP_SETTLE_MS，让先按下的修饰键先生效
 *   └─ 超过 KEY_PRESS_FIVE_FINGER_GUARD_MS → 激活（按住不放的键 / 修饰键）
 * ```
 *
 * 代价只是「按住不放」的键最多晚 [GestureConstants.KEY_PRESS_FIVE_FINGER_GUARD_MS]
 * 生效；单指**轻点**零劣化（点起那一刻屏幕上已无其它指针，立即激活并松开）。
 *
 * 覆盖的按键入口：87 键键盘页（`KeyboardScreen`）、竖屏融合页修饰键排
 * （`FusedModifierRow`）、小键盘 sheet（`NumpadSheet`）——三者都走同一个
 * [com.pocketkeyboard.app.keyboard.KeyCap] → 本 modifier，因此**没有**任何一颗键
 * 能绕过这道门控。
 *
 * @param handler 按键回调
 * @param pressGuardMs 激活门控时长；传 `0` 完全关闭（恢复「落下即激活」的旧行为）。
 *    触控板底部点击区（鼠标左右键）用更短的值——它不是字符键，误触代价也低。
 * @param enabled false 时完全禁用指针输入（例如按键处于禁用态）
 */
@Composable
fun Modifier.pocketKeyGestures(
    handler: PocketKeyGestureHandler,
    pressGuardMs: Long = GestureConstants.KEY_PRESS_FIVE_FINGER_GUARD_MS,
    enabled: Boolean = true,
): Modifier {
    // rememberUpdatedState：pointerInput 的 key 固定为 pressGuardMs，handler 每次重组都变
    // 也不重启手势协程——否则「按住一个键期间页面发生重组」（例如 DataStore 偏好首次回填、
    // fn 状态变化）会取消正在进行的按下，onRelease 永远不会调用，键帽卡在按下态、
    // 被控设备也卡在这个键上。回调始终通过 currentHandler 拿到最新实例。
    // 与 Modifier.pocketGestures / Modifier.trackpadGestures 同一套写法。
    val currentHandler by rememberUpdatedState(handler)
    return this.then(
        if (enabled) {
            Modifier.pointerInput(pressGuardMs) {
                awaitEachGesture {
                    // requireUnconsumed = true：只接「没人要」的指针，保证多键和弦时每指只命中一个键
                    val down = awaitFirstDown(requireUnconsumed = true)
                    // 单指按下立刻消费，宣告这根手指归本按键
                    down.consume()
                    // 视觉 / 触觉反馈立刻给，与激活门控无关（否则门控期间键帽像失灵）
                    currentHandler.onTouchDown()
                    awaitKeyActivation(
                        down = down,
                        pressGuardMs = pressGuardMs,
                        handler = currentHandler,
                    )
                }
            }
        } else {
            Modifier
        },
    )
}

/** 一次 `awaitPointerEvent` 观察到的指针快照。 */
private data class PointerSnapshot(
    val pressedCount: Int,
    val ourPointerDown: Boolean,
)

/**
 * 门控状态机的阶段。
 *
 * 名字即语义：`GUARD` 等激活时机；`SETTLE` 本指已抬起、等别人按下的那颗键先生效；
 * `RELEASE` 已激活、等本指抬起补发松键；`DONE` 本轮结束（任何后续观察都忽略）。
 */
internal enum class KeyGatePhase { GUARD, SETTLE, RELEASE, DONE }

/** 门控状态机对一次观察给出的动作（纯数据，便于单测逐条断言）。 */
internal sealed interface KeyGateAction {
    /** 继续等下一个事件 / 超时。 */
    data object Wait : KeyGateAction

    /** 激活这颗键（`onPress`）。 */
    data object Activate : KeyGateAction

    /** 激活并立刻松开（孤立轻点 / 收敛期满的轻点）。 */
    data object ActivateAndRelease : KeyGateAction

    /** 5 指凑齐：本次按下整体作废，一次报告都不发。 */
    data object Abandon : KeyGateAction

    /** 已激活的键正常抬起：补发松键。 */
    data object Release : KeyGateAction
}

/**
 * 一次按键按下的激活门控状态机（纯 Kotlin，不碰 Compose / Android）。
 *
 * 说明见 [pocketKeyGestures] 的「五指挥势防误触」小节；这里只做**判定**，把
 * 「等事件 + 掐时间」留在挂起函数里，于是整套防误触逻辑可以在 JVM 单测里逐条跑。
 *
 * @param requiredFingerCount 判定为五指挥势的手指数（注入以便测试）
 */
internal class KeyGateState(
    private val requiredFingerCount: Int = GestureConstants.REQUIRED_FINGER_COUNT,
) {
    /** 本次按下见证过的最多按下指针数（含掌心多报的那根）。 */
    var peakPointerCount: Int = 1
        private set

    /** 当前阶段。 */
    var phase: KeyGatePhase = KeyGatePhase.GUARD
        private set

    /** 是否已激活（门控通过、调用过 `onPress`）。 */
    var activated: Boolean = false
        private set

    /**
     * 喂入一次指针观察。
     *
     * @param pressedCount 当前按下指针数
     * @param ourPointerDown 本按键这根手指是否仍按着
     * @param timedOut 当前阶段的等待是否已到期（超时）
     * @return 应当执行的动作
     */
    fun onEvent(pressedCount: Int, ourPointerDown: Boolean, timedOut: Boolean): KeyGateAction {
        if (pressedCount > peakPointerCount) peakPointerCount = pressedCount
        if (phase == KeyGatePhase.DONE) return KeyGateAction.Wait

        // 5 指凑齐 → 作废。这是「5 指按下时键盘不激活」的硬保证：只要在本键激活前
        // 观察到 5 指，`onPress` 就一次都不会被调用
        if (pressedCount >= requiredFingerCount) {
            phase = KeyGatePhase.DONE
            return KeyGateAction.Abandon
        }

        return when (phase) {
            KeyGatePhase.GUARD -> when {
                // 本指抬起、屏幕上已无其它指针 → 孤立轻点：立即激活并松开。
                // 正常打字的观感与门控前完全一致（零额外延迟）
                !ourPointerDown && pressedCount == 0 -> {
                    phase = KeyGatePhase.DONE
                    activated = true
                    KeyGateAction.ActivateAndRelease
                }

                // 本指抬起、但还有别人按着 → 收敛：等那颗先按下的键先生效
                !ourPointerDown -> {
                    phase = KeyGatePhase.SETTLE
                    KeyGateAction.Wait
                }

                // 有人抬起（峰值下降）→ 不可能是五指伸展，立刻激活。
                // 典型「按住 ctrl 再点 c」：点 c 的手指一抬起就满足
                pressedCount < peakPointerCount -> {
                    phase = KeyGatePhase.RELEASE
                    activated = true
                    KeyGateAction.Activate
                }

                // 门控期满：还没凑到 5 指，按普通按下处理
                timedOut -> {
                    phase = KeyGatePhase.RELEASE
                    activated = true
                    KeyGateAction.Activate
                }

                else -> KeyGateAction.Wait
            }

            KeyGatePhase.SETTLE -> if (timedOut) {
                // 收敛期也没等到 5 指：安全激活（和弦的另一半）
                phase = KeyGatePhase.DONE
                activated = true
                KeyGateAction.ActivateAndRelease
            } else {
                KeyGateAction.Wait
            }

            KeyGatePhase.RELEASE -> when {
                // 激活后才凑齐 5 指：立刻松键，别把被控设备卡在按下的键上
                pressedCount >= requiredFingerCount -> {
                    phase = KeyGatePhase.DONE
                    KeyGateAction.Abandon
                }

                !ourPointerDown -> {
                    phase = KeyGatePhase.DONE
                    KeyGateAction.Release
                }

                else -> KeyGateAction.Wait
            }

            KeyGatePhase.DONE -> KeyGateAction.Wait
        }
    }
}

/**
 * 按下之后的激活门控主循环（挂起部分，说明见 [pocketKeyGestures]）。
 *
 * 单独拆成 `AwaitPointerEventScope` 的扩展函数而不是写在 `awaitEachGesture {}` 的 lambda
 * 里：后者带 `@RestrictsSuspension`，lambda 内只能调用以该作用域为接收者的挂起函数，
 * `withTimeoutOrNull` 放不进去。
 *
 * 判定全部委托给纯函数 [KeyGateState.onEvent]；这里只负责「等事件、掐阶段时间、
 * 把动作翻译成回调」。**不读系统时钟**：超时用 `withTimeoutOrNull` 表达，与
 * `MotionEvent` 的时间戳体系无关。
 *
 * @param down 本按键接下的那根手指（已 consume）
 * @param pressGuardMs 门控时长；≤0 表示立即激活（旧行为）
 */
private suspend fun AwaitPointerEventScope.awaitKeyActivation(
    down: PointerInputChange,
    pressGuardMs: Long,
    handler: PocketKeyGestureHandler,
) {
    val settleMs = GestureConstants.KEY_TAP_SETTLE_MS
    val guardMs = pressGuardMs.coerceAtLeast(0L)
    val gate = KeyGateState()
    val tracker = KeyPointerTracker(down.id)

    while (gate.phase != KeyGatePhase.DONE) {
        // 各阶段的等待上限：GUARD = 五指防误触门控；SETTLE = 和弦收敛；RELEASE = 不限时
        val timeoutMs = when (gate.phase) {
            KeyGatePhase.GUARD -> guardMs.takeIf { it > 0L }
            KeyGatePhase.SETTLE -> settleMs
            else -> null
        }
        // 超时（事件没来）时按下指针数不可能变化，沿用已见证的峰值即可
        val snapshot = with(tracker) { pollNext(timeoutMs) }
        val action = gate.onEvent(
            pressedCount = snapshot?.pressedCount ?: gate.peakPointerCount,
            ourPointerDown = snapshot?.ourPointerDown ?: true,
            timedOut = snapshot == null,
        )
        when (action) {
            KeyGateAction.Wait -> Unit
            KeyGateAction.Activate -> handler.onPress()
            KeyGateAction.ActivateAndRelease -> {
                handler.onPress()
                handler.onRelease()
            }

            KeyGateAction.Abandon -> handler.onAbandoned()
            KeyGateAction.Release -> handler.onRelease()
        }
    }
}

/**
 * 读指针事件的小工具（一次按键按下期间复用）。
 *
 * 单独一个类：把读事件的逻辑封成成员挂起函数，才能躲开
 * `AwaitPointerEventScope` 的 `@RestrictsSuspension` 限制（局部 suspend fun 里调用
 * `withTimeoutOrNull { awaitPointerEvent() }` 编译不过）。
 */
private class KeyPointerTracker(private val downId: PointerId) {

    /**
     * 等一个指针事件并回报快照。
     *
     * @param timeoutMs null = 一直等到有事件；0 = 不等待立即返回 null；>0 = 最多等这么久
     * @return null 表示超时（或不想等）；否则是本次事件观察到的指针快照
     */
    suspend fun AwaitPointerEventScope.pollNext(timeoutMs: Long?): PointerSnapshot? {
        val event = when (timeoutMs) {
            null -> awaitPointerEvent()
            else -> if (timeoutMs > 0L) {
                withTimeoutOrNull(timeoutMs) { awaitPointerEvent() }
            } else {
                null
            }
        } ?: return null
        val pressed = event.changes.filter { it.pressed }
        return PointerSnapshot(
            pressedCount = pressed.size,
            ourPointerDown = pressed.any { it.id == downId },
        )
    }
}
