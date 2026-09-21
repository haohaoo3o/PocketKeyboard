package com.pocketkeyboard.app.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 键盘按键层的指针回调。
 *
 * @param onPress 单指按下且该指针未被其他节点消费时触发（可以在这里发键盘报告）
 * @param onRelease 同一根手指正常抬起时触发（发「松开全部按键」报告）
 * @param onAbandoned 按下指针数达到 [GestureConstants.REQUIRED_FINGER_COUNT] 时触发：
 *    本次按下被判定为五指挥势，按键必须作废（补发一次松键，避免卡键）
 */
class PocketKeyGestureHandler(
    val onPress: () -> Unit = {},
    val onRelease: () -> Unit = {},
    val onAbandoned: () -> Unit = {},
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
 * Main   ：叶 → 根   子先消费；子消费掉的事件父仍会收到，但 isConsumed == true
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
 * @param handler 按键回调
 * @param enabled false 时完全禁用指针输入（例如按键处于禁用态）
 */
@Composable
fun Modifier.pocketKeyGestures(
    handler: PocketKeyGestureHandler,
    enabled: Boolean = true,
): Modifier {
    // rememberUpdatedState：pointerInput 的 key 固定为 Unit，handler 每次重组都变也不重启
    // 手势协程——否则「按住一个键期间页面发生重组」（例如 DataStore 偏好首次回填、
    // fn 状态变化）会取消正在进行的按下，onRelease 永远不会调用，键帽卡在按下态、
    // 被控设备也卡在这个键上。回调始终通过 currentHandler 拿到最新实例。
    // 与 Modifier.pocketGestures / Modifier.trackpadGestures 同一套写法。
    val currentHandler by rememberUpdatedState(handler)
    return this.then(
        if (enabled) {
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    // requireUnconsumed = true：只接「没人要」的指针，保证多键和弦时每指只命中一个键
                    val down = awaitFirstDown(requireUnconsumed = true)
                    // 单指按下立刻消费，宣告这根手指归本按键
                    down.consume()
                    currentHandler.onPress()

                    var abandoned = false
                    while (true) {
                        val event = awaitPointerEvent()
                        // ① 按下指针数 ≥ 5：判定为五指挥势，放行给底层手势层
                        if (event.changes.count { it.pressed } >= GestureConstants.REQUIRED_FINGER_COUNT) {
                            abandoned = true
                            break
                        }
                        // ② 本指抬起：正常结束一次点击
                        if (event.changes.none { it.id == down.id && it.pressed }) break
                    }

                    if (abandoned) currentHandler.onAbandoned() else currentHandler.onRelease()
                }
            }
        } else {
            Modifier
        },
    )
}
