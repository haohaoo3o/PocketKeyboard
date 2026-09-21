package com.pocketkeyboard.app.gesture

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** 一次触摸手势的归属。 */
enum class GestureOwner {
    /** 尚未裁定：手势刚开始，五指手势层和触控板层都在观望。 */
    UNDECIDED,

    /** 归五指手势层（收缩 / 张开 / 整体横滑 → 切模式 / 切设备）。 */
    FIVE_FINGER,

    /** 归触控板层的 1–4 指手势（滚动 / 点击 / 拖动）。 */
    TRACKPAD,
}

/**
 * 手势仲裁器：决定「一次触摸」到底归五指手势层还是归触控板层。
 *
 * ## 为什么需要它
 *
 * 五指手势层挂在页面容器最底层（`Modifier.pocketGestures` 挂在承载
 * AnimatedContent 的 Box 上），触控板层是它的子节点。Compose 的指针事件分发顺序是：
 *
 * ```
 * 一次 PointerEvent 会按三个 pass 依次送达同一棵 hit-test 路径上的所有节点：
 *   Initial：根 → 叶（父先看到，父可以在这里抢先 consume，子节点随后就看不到了）
 *   Main   ：叶 → 根（子先看到，子消费掉的事件父节点仍会收到但 isConsumed == true）
 *   Final  ：根 → 叶（父最后看到，用于兜底清理）
 * ```
 *
 * 也就是说，Main pass 里触控板层（子）天然比五指手势层（父）先拿到事件。如果两边各自为政，
 * 一次「先放一根手指、再快速补四根」的触摸会被触控板层当成 1 指拖动，同时又被五指层当成
 * 收缩手势，两边同时动作、互相打架。
 *
 * ## 仲裁规则（写在产品需求里，实现必须严格遵守）
 *
 * > 按下后 60ms 内凑满 5 指则归五指手势层，否则归触控板手势。
 *
 * 实现方式：五指手势层在检测到第 5 根手指落下时调用 [claimFiveFinger]；
 * 触控板层在手势最开始调用 [awaitGestureOwner] 挂起等待，直到
 *  1. 五指层声明接管 → 返回 [GestureOwner.FIVE_FINGER]，触控板层直接放弃本次手势、
 *     不消费任何 change，事件自然继续留给五指层；
 *  2. 仲裁窗口耗尽 → 返回 [GestureOwner.TRACKPAD]，触控板层开始处理自己的 1–4 指手势。
 *
 * 因为触控板层是「先挂起再动作」，所以即使它在 Main pass 里先看到第一根手指的 down，
 * 也绝不会在五指层表态之前消费事件 —— 这正是本类存在的意义。
 *
 * ## 使用方式（触控板工程师照抄即可）
 *
 * ```kotlin
 * // 与 Modifier.pocketGestures 共用同一个 arbiter 实例，否则仲裁无效
 * val arbiter = remember { GestureArbiter() }
 *
 * Box(
 *     modifier = Modifier
 *         .fillMaxSize()
 *         .pointerInput(arbiter) {
 *             awaitEachGesture {
 *                 val down = awaitFirstDown(requireUnconsumed = true)
 *                 // 注意：必须用 awaitGestureOwner，不能直接调 arbiter.awaitDecision()
 *                 // （AwaitPointerEventScope 是 @RestrictsSuspension，详见该函数注释）
 *                 if (awaitGestureOwner(arbiter, down.uptimeMillis) == GestureOwner.FIVE_FINGER) {
 *                     // 归五指层：不要 consume，直接结束本轮 awaitEachGesture
 *                     return@awaitEachGesture
 *                 }
 *                 // 归触控板层：从这里开始写 1–4 指手势
 *                 // 1 指拖动 → HidTransport.sendMouseMove(dx, dy)
 *                 // 2 指拖动 → HidTransport.sendScroll(dx, dy)
 *                 // 单击      → HidTransport.sendMouseButton(LEFT, true/false)
 *             }
 *         },
 * )
 * ```
 *
 * ## 生命周期
 *
 * [reset] 必须在一次手势全部手指抬起后调用，否则 [GestureOwner] 会停留在上一轮的结果，
 * 下一轮手势会被误判。`Modifier.pocketGestures` 的五指检测器内部已经负责在「所有手指抬起」
 * 后调用 [reset]，因此只要触控板层自己不滥用本类就不需要额外处理。
 *
 * @param arbitrationWindow 仲裁窗口，默认取 [GestureConstants.ARBITRATION_WINDOW_MS]（60ms）。
 */
class GestureArbiter(
    private val arbitrationWindow: Duration =
        GestureConstants.ARBITRATION_WINDOW_MS.milliseconds,
) {

    private val _owner = MutableStateFlow(GestureOwner.UNDECIDED)

    /** 当前手势归属，可供 UI 层观测（例如调试时把归属画在屏幕上）。 */
    val owner: StateFlow<GestureOwner> = _owner.asStateFlow()

    /** 便捷判断：本次手势是否已被五指手势层接管。 */
    val isFiveFingerActive: Boolean
        get() = _owner.value == GestureOwner.FIVE_FINGER

    /**
     * 触控板层调用：标记一次新手势开始（第一根手指按下）。
     *
     * 正常流程里，五指手势层会在「所有手指抬起」后调用 [reset]，所以新手势开始时状态本就该是
     * [GestureOwner.UNDECIDED]。这里仍然兜底清一次：万一上一轮手势的归属没被复位（例如五指层
     * 被禁用、或触控板页被单独复用），也不要把旧结果带给新一轮手势。
     */
    fun onGestureStart() {
        _owner.value = GestureOwner.UNDECIDED
    }

    /**
     * 五指手势层调用：声明接管本次手势。
     *
     * 只有在窗口内凑满 [GestureConstants.REQUIRED_FINGER_COUNT] 指时才允许调用
     * （由调用方保证，五指检测器用 [GestureConstants.FINGER_GATHER_WINDOW_MS] 掐表）。
     */
    fun claimFiveFinger() {
        _owner.value = GestureOwner.FIVE_FINGER
    }

    /** 五指手势层调用：明确放弃本次手势，交还给触控板层。 */
    fun releaseToTrackpad() {
        if (_owner.value == GestureOwner.UNDECIDED) {
            _owner.value = GestureOwner.TRACKPAD
        }
    }

    /**
     * 触控板层调用：挂起直到仲裁结果确定。
     *
     * - 五指层先表态 → 立刻返回 [GestureOwner.FIVE_FINGER]；
     * - 超过 [arbitrationWindow] 仍无表态 → 返回 [GestureOwner.TRACKPAD]（超时是安全默认值：
     *   即便五指检测器因故没有运行，触控板层也只会多等一个窗口时长，不会永久卡住）。
     */
    suspend fun awaitDecision(): GestureOwner {
        val alreadyDecided = _owner.value
        if (alreadyDecided != GestureOwner.UNDECIDED) return alreadyDecided
        return withTimeoutOrNull(arbitrationWindow.inWholeMilliseconds) {
            _owner.first { it != GestureOwner.UNDECIDED }
        } ?: GestureOwner.TRACKPAD
    }

    /**
     * 任一层调用：一次手势结束（所有手指抬起）后复位，准备接受下一次手势。
     */
    fun reset() {
        _owner.value = GestureOwner.UNDECIDED
    }
}

/**
 * 在 `awaitEachGesture { }` 内部等待仲裁结果。
 *
 * ## 为什么不能直接 `arbiter.awaitDecision()`
 *
 * `AwaitPointerEventScope` 带有 `@RestrictsSuspension`：在它的 lambda 里**只能**调用以它为
 * 接收者的挂起函数（`awaitPointerEvent` / `awaitFirstDown` 等）。直接调用别的对象上的挂起
 * 函数会报 "Restricted suspending functions can invoke member or extension suspending
 * functions only on their restricted coroutine scope"，`delay` / `coroutineScope` 都一样过不了。
 *
 * ## 为什么用「事件驱动的轮询」
 *
 * Main pass 的顺序是「叶 → 根」，触控板层（子）会比五指挥势层（父）**先**看到第 5 根手指的
 * 按下事件。五指层因此读 **Initial pass**（根 → 叶），保证它在子层的 Main pass 之前就调用了
 * [GestureArbiter.claimFiveFinger]；本函数每拿到一个事件就复查一次 `owner`，于是最多一个事件
 * 的延迟就能拿到正确结果。
 *
 * 超时兜底：窗口耗尽仍未表态 → 返回 [GestureOwner.TRACKPAD]（即使五指检测器因故没挂载，
 * 触控板层也只会多等一个窗口时长，不会永久卡住）。
 *
 * ```kotlin
 * awaitEachGesture {
 *     val down = awaitFirstDown(requireUnconsumed = true)
 *     if (awaitGestureOwner(arbiter, down.uptimeMillis) == GestureOwner.FIVE_FINGER) {
 *         return@awaitEachGesture   // 不消费，放行给五指挥势层
 *     }
 *     // 归本层：从这里开始写 1–4 指手势
 * }
 * ```
 */
suspend fun AwaitPointerEventScope.awaitGestureOwner(
    arbiter: GestureArbiter,
    firstDownUptime: Long,
): GestureOwner {
    arbiter.owner.value.let { decided ->
        if (decided != GestureOwner.UNDECIDED) return decided
    }
    var latestUptime = firstDownUptime
    val deadline = firstDownUptime + GestureConstants.ARBITRATION_WINDOW_MS
    while (true) {
        val decided = arbiter.owner.value
        if (decided != GestureOwner.UNDECIDED) return decided
        val remaining = deadline - latestUptime
        if (remaining <= 0L) return GestureOwner.TRACKPAD
        val event = withTimeoutOrNull(remaining) { awaitPointerEvent() } ?: return GestureOwner.TRACKPAD
        latestUptime = event.changes.maxOfOrNull { it.uptimeMillis } ?: latestUptime
    }
}
