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
 * @param onAbandoned 按下被判定为五指挥势时触发：本次按下整体作废，按键必须回弹
 *    （视觉下陷撤销；门控期间从未激活过，因此不需要补发松键）
 * @param onTouchDown 手指刚落下、还没过五指防误触门控时触发。只用于**视觉 / 触觉**
 *    反馈（键帽下陷、震动），不承载任何激活语义——真正「这个键生效了」始终看 [onPress]。
 *    没有它，门控期间键帽不会有下陷反馈，观感上就像按键失灵。
 * @param onCancelled 手势被**系统取消**（ACTION_CANCEL）或协程被取消时的兜底回调：
 *    `awaitKeyActivation` 一个动作回调都不会走，若不在这里撤销下陷，键帽会永久卡在
 *    按下态——真机上「五指误触」的视觉残留有相当一部分来自这条路径。正常走完
 *    activate / release / abandon 时它也会被调用，实现保持幂等即可
 */
class PocketKeyGestureHandler(
    val onPress: () -> Unit = {},
    val onRelease: () -> Unit = {},
    val onAbandoned: () -> Unit = {},
    val onTouchDown: () -> Unit = {},
    val onCancelled: () -> Unit = {},
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
 * - 判定为五指挥势时，按键**直接放弃本轮手势**（不再 consume 任何 change），让父节点的
 *   五指挥势层完整拿到全部手指的位置，从而识别收缩 / 张开 / 整体横滑。判定有两条路：
 *   ① 本节点自己数到 5 根手指（多指压在同一颗小键上）；② **共享闸门**报出五指意图
 *   （见下面「兄弟盲区」小节，正常 5 指分散落在不同键上时只有这条路有效）。
 * - 因为父节点读 **Initial pass**（早于本层的 Main pass）且不检查 isConsumed，
 *   按键「放行」的动作不需要任何额外 API，只要自己不 consume 即可。
 *
 * 换句话说：**子层（按键）负责「单指时独占、判定为五指挥势时让路」，父层（五指挥势）
 * 负责「看到 5 指就接管」**，两边靠 pass 顺序和 [FiveFingerGate] 协同——注意本层并不
 * 直接持有父层引用，只读 `LocalFiveFingerGate`。
 *
 * ## 五指挥势防误触：按下后先「按住不激活」（问题 3b / 第三轮兄弟盲区）
 *
 * 上面的规则只保证「判定为五指挥势后按键不再响应」，但按键在**第 1 根手指**落下时就激活了
 * （`onPress`）——用户做一次五指捏合，落在键帽上的那几颗键会先各打一次给被控设备，
 * 然后才被作废。这就是用户反馈的「五指挥势误触键盘」。
 *
 * 因此按下后先不激活，进入 [awaitKeyActivation] 的门控状态机：
 *
 * ```
 * 手指落下（consume，进入门控）
 *   ├─ 判定为五指挥势            → onAbandoned，一次报告都不发，视觉下陷回弹
 *   │    （本节点数到 5 指，或闸门报出 ≥3 指意图 / claimed）
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
 * ## 兄弟盲区：为什么必须读共享闸门（真机实测第二轮的主根因）
 *
 * Compose 的 `HitPathTracker` 按**指针各自**做 hit-test：一根手指只沿它自己命中的那条
 * 路径下发，落在别的键帽 / 触控板 / IME 上的手指**不会**出现在本节点的
 * `PointerEvent.changes` 里。于是「5 根手指分别落在 5 个不同键帽上」时，每颗键的
 * 本地 `pressedCount` 永远是 1——按键层自己永远等不到 `pressedCount >= 5`，
 * 门控期满后照样激活，用户看到的就是「五指放上键盘仍然误触」。
 *
 * 父节点（页面容器最底层的 `Modifier.pocketGestures`）读 **Initial pass**，它挂在
 * 所有指针的公共路径上，看得到全部手指——那份全局计数经 [FiveFingerGate] 下发
 * （`observeGather` 是全局真值）。因此本 modifier 注入 `LocalFiveFingerGate`，
 * 每次判定前读 `gate.pendingFingers` / `gate.claimed`：
 *
 * - `gate.intentDetected`（≥ [GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT] 指意图）
 *   或 `claimed`（五指层已接管）→ **立刻 Abandon**，视觉按下态同步回弹；
 * - 本节点自己的指针数达到 [GestureConstants.REQUIRED_FINGER_COUNT]（多根手指压在
 *   同一颗小键上）同样 Abandon，这条本地兜底不受闸门影响。
 *
 * 同时门控等待按 [GATE_POLL_SLICE_MS] 切片轮询：本键节点在「只有别人在落指」时
 * 收不到任何事件，不切片就要等 300ms 门控期满才发现闸门早就报出了意图。
 *
 * 没被 `CompositionLocalProvider` 包住的组合（单测里的局部页面）拿到的是空闸门实例
 * （`pendingFingers = 0` / `claimed = false`），行为自动退回纯本地判定。
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
    // 五指挥势意图闸门（见 [pocketKeyGestures] 文档里的「兄弟盲区」小节）：
    // MainActivity 在应用根部 CompositionLocalProvider 下发同一个实例，父层
    // pocketGestures 在 Initial pass 里持续写入全局手指数；这里只读不写。
    // 没有 provider 时拿到空实例（永远不报意图），行为退回纯本地判定。
    val currentGate by rememberUpdatedState(LocalFiveFingerGate.current)
    return this.then(
        if (enabled) {
            Modifier.pointerInput(pressGuardMs) {
                awaitEachGesture {
                    // requireUnconsumed = true：只接「没人要」的指针，保证多键和弦时每指只命中一个键
                    val down = awaitFirstDown(requireUnconsumed = true)
                    // 单指按下立刻消费，宣告这根手指归本按键
                    down.consume()
                    // 视觉 / 触觉反馈立刻给，与激活门控无关（否则门控期间键帽像失灵）。
                    // 若这次触摸随后被闸门判成五指挥势，onAbandoned 会把下陷撤销回去
                    currentHandler.onTouchDown()
                    try {
                        awaitKeyActivation(
                            down = down,
                            pressGuardMs = pressGuardMs,
                            handler = currentHandler,
                            gate = { currentGate },
                        )
                    } finally {
                        // 兜底：手势被系统取消（ACTION_CANCEL——真机上多指常被系统手势
                        // 监视器截走）或协程被取消时，awaitKeyActivation 一个回调都不会走，
                        // 键帽会永久卡在下陷态。正常路径下 onRelease / onAbandoned 已经把
                        // 按下态清掉，这里再调一次是幂等的
                        currentHandler.onCancelled()
                    }
                }
            }
        } else {
            Modifier
        },
    )
}

/**
 * 门控等待的**闸门轮询切片**：GUARD / SETTLE 阶段最多等这么久就醒来看一眼共享闸门。
 *
 * 为什么需要切片：本键节点只能收到命中自己的指针的事件（兄弟节点互不可见对方指针）。
 * 「别人还在陆续落指」时本层一个事件都收不到，若用一整个 300ms 门控去等，父层早已
 * 报出的五指挥势意图要等到门控期满才发现——键已经误触激活了。切成 16ms（约一帧）
 * 后，意图一出现最多一帧之内这颗键就作废回弹。
 */
private const val GATE_POLL_SLICE_MS = 16L

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
 * 说明见 [pocketKeyGestures] 的「五指挥势防误触」与「兄弟盲区」小节；这里只做**判定**，
 * 把「等事件 + 掐时间」留在挂起函数里，于是整套防误触逻辑可以在 JVM 单测里逐条跑。
 *
 * ## 判定输入有两套
 *
 * - `pressedCount` / `ourPointerDown`：**本节点**看到的指针（兄弟盲区，通常只有自己那根）。
 *    激活时机的判定（峰值下降 / 孤立轻点 / 门控期满）只用这套，与闸门接入前完全一致；
 * - `pendingFingers` / `claimed`：**共享闸门** [FiveFingerGate] 的全局计数，由父层
 *   `Modifier.pocketGestures` 在 Initial pass 写入，是本层唯一的「全局真值」。
 *   只用于「是不是五指挥势」的作废判定，不参与激活时机——否则第 2 根手指落在别的键上时
 *   会被误判成「有人抬起、峰值下降」而抢跑激活，正是要防的误触。
 *
 * 多指落在不同键帽上时前者永远是 1，所以「是不是五指挥势」由后者回答；
 * 多指压在**同一颗**小键上时前者自己就能数到 5，两条路都通向 [KeyGateAction.Abandon]。
 *
 * @param requiredFingerCount 本地指针数判定为五指挥势的手指数（注入以便测试）
 * @param intentFingerCount 闸门「有五指挥势意图」的手指数阈值（注入以便测试）
 */
internal class KeyGateState(
    private val requiredFingerCount: Int = GestureConstants.REQUIRED_FINGER_COUNT,
    private val intentFingerCount: Int = GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT,
) {
    /** 本次按下本节点见证过的最多按下指针数（含掌心多报的那根）。只数**本节点**看得见的指针。 */
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
     * @param pressedCount 本节点当前按下的指针数
     * @param ourPointerDown 本按键这根手指是否仍按着
     * @param timedOut 当前阶段的等待是否已到期（超时）
     * @param pendingFingers 共享闸门在凑指窗口内观察到的最大手指数（0 = 没有闸门 / 未上报）
     * @param claimed 本次触摸是否已被五指层接管（凑满 5 指）
     * @return 应当执行的动作
     */
    fun onEvent(
        pressedCount: Int,
        ourPointerDown: Boolean,
        timedOut: Boolean,
        pendingFingers: Int = 0,
        claimed: Boolean = false,
    ): KeyGateAction {
        // 峰值只数**本节点**看得见的指针：把闸门的全局计数并进来会让「第 2 根手指落在
        // 别的键上」被误判成「有人抬起（峰值下降）」，按键立刻抢跑激活——那正是要防的误触。
        // 闸门只参与「是不是五指挥势」的作废判定，不参与激活时机的判定
        if (pressedCount > peakPointerCount) peakPointerCount = pressedCount
        if (phase == KeyGatePhase.DONE) return KeyGateAction.Wait

        // 五指挥势判定（两条路都算）：
        // ① 本节点自己数到 requiredFingerCount（多指压在同一颗小键上）；
        // ② 共享闸门：`claimed`（五指层已凑满接管）或 `pendingFingers` 达到意图阈值
        //    （父层看得到全部手指，本节点看不到的兄弟指针在这里被计入）。
        //    判成意图就作废，`onPress` 一次都不会被调用——这是「五指放在键盘上
        //    不误触」的硬保证；视觉下陷由调用方的 onAbandoned 立即撤销。
        val fiveFingerIntent = claimed ||
            pendingFingers >= intentFingerCount
        if (pressedCount >= requiredFingerCount || fiveFingerIntent) {
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
            // 典型「按住 ctrl 再点 c」：点 c 的手指一抬起就满足。峰值只数本节点看得见的
            // 指针（兄弟节点互不可见），因此这条只覆盖「同一节点上的手指抬起」——
            // 跨键和弦的修饰键仍走门控期满激活，与闸门接入前一致
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
                // 激活后又出现五指挥势（本地数到 5 / 闸门报出意图）→ 上面的统一判定
                // 已经返回 Abandon 并结束本轮，这里只剩「正常抬起补发松键」
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
 * `MotionEvent` 的时间戳体系无关；阶段是否到期用「连续没等到事件的累计时长」衡量。
 *
 * @param down 本按键接下的那根手指（已 consume）
 * @param pressGuardMs 门控时长；≤0 表示立即激活（旧行为）
 * @param gate 取共享闸门（[LocalFiveFingerGate] 的当前实例）。默认 null = 没有闸门，
 *     行为退回纯本地判定（单测 / 未挂载手势层的页面）
 */
private suspend fun AwaitPointerEventScope.awaitKeyActivation(
    down: PointerInputChange,
    pressGuardMs: Long,
    handler: PocketKeyGestureHandler,
    gate: () -> FiveFingerGate? = { null },
) {
    val settleMs = GestureConstants.KEY_TAP_SETTLE_MS
    val guardMs = pressGuardMs.coerceAtLeast(0L)
    val state = KeyGateState()
    val tracker = KeyPointerTracker(down.id)

    // 本阶段「一个事件都没等到」的累计时长。收到事件就归零（与改动前「每轮重新掐表」
    // 的语义一致），攒满阶段上限才算阶段到期——因此 16ms 的闸门轮询切片不会被
    // 误判成门控期满（否则按住不放的键会在一帧之后就激活）
    var noEventElapsed = 0L

    while (state.phase != KeyGatePhase.DONE) {
        // 各阶段的等待上限：GUARD = 五指防误触门控；SETTLE = 和弦收敛；RELEASE = 不限时
        val timeoutMs = when (state.phase) {
            KeyGatePhase.GUARD -> guardMs.takeIf { it > 0L }
            KeyGatePhase.SETTLE -> settleMs
            else -> null
        }
        // GUARD / SETTLE 按 GATE_POLL_SLICE_MS 切片等：本键节点在「只有别人在落指」时
        // 收不到任何事件（兄弟节点互不可见对方指针），不切片就要等整个阶段期满才发现
        // 闸门早已报出的五指挥势意图——那时键已经误触激活了。RELEASE 不限时也无需切片：
        // 已激活的键就该一直按着，直到本指抬起
        val sliceMs = when (state.phase) {
            KeyGatePhase.GUARD, KeyGatePhase.SETTLE ->
                timeoutMs?.let { minOf(it, GATE_POLL_SLICE_MS) } ?: GATE_POLL_SLICE_MS
            else -> null
        }
        val snapshot = with(tracker) { pollNext(sliceMs) }
        if (snapshot == null && sliceMs != null) noEventElapsed += sliceMs else noEventElapsed = 0L
        val phaseExpired = timeoutMs != null && noEventElapsed >= timeoutMs

        // 每次判定前读共享闸门：父层在 Initial pass（早于本层的 Main pass）更新它，
        // 同一批事件里本层已经看得到新落下的手指；切片超时的那些轮次也一样读
        val currentGate = gate()
        val pendingFingers = currentGate?.pendingFingers ?: 0
        val claimed = currentGate?.claimed ?: false
        val action = state.onEvent(
            // 超时（事件没来）时按下指针数不可能变化，沿用已见证的峰值即可
            pressedCount = snapshot?.pressedCount ?: state.peakPointerCount,
            ourPointerDown = snapshot?.ourPointerDown ?: true,
            timedOut = phaseExpired,
            pendingFingers = pendingFingers,
            claimed = claimed,
        )
        when (action) {
            KeyGateAction.Wait -> Unit
            KeyGateAction.Activate -> {
                PocketGestureLog.trace("按键激活（门控通过：pendingFingers=$pendingFingers claimed=$claimed）")
                handler.onPress()
            }
            KeyGateAction.ActivateAndRelease -> {
                PocketGestureLog.trace(
                    "按键激活并松开（孤立轻点 / 收敛期满：pendingFingers=$pendingFingers claimed=$claimed）",
                )
                handler.onPress()
                handler.onRelease()
            }

            KeyGateAction.Abandon -> {
                // 真机排查「五指放上键盘是否误触」的观测点：这一行出现 = 这颗键作废了，
                // onPress 从未被调用（一次报告都不发），键帽下陷同步回弹
                PocketGestureLog.decision(
                    "按键作废 → 不激活（本节点按下 ${snapshot?.pressedCount ?: 0} 指，" +
                        "闸门 pendingFingers=$pendingFingers claimed=$claimed）",
                )
                handler.onAbandoned()
            }
            KeyGateAction.Release -> {
                PocketGestureLog.trace("按键松开（本指抬起）")
                handler.onRelease()
            }
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
