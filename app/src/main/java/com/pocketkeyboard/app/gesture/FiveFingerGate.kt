package com.pocketkeyboard.app.gesture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 五指挥势的「意图闸门」：把五指挥势层在 `pointerInput` 协程里看到的状态，
 * 暴露给 Compose UI 层（输入法唤起区、dock、按键层）读取。
 *
 * ## 为什么需要它
 *
 * 竖屏融合页唤起系统键盘后，屏幕下半是 **IME 窗口**——落在它上面的手指直接进输入法，
 * 到不了 App 的手势层。于是「用 5 根手指跨屏捏合」永远凑不齐 5 指：上一轮把凑指窗口
 * 改成滑动续期（300ms 续期 / 800ms 上限）后真机**依然不灵**，根因就在这里，而不是
 * 窗口时长不够。对策是在手势层一看到「多指正在汇聚」就**主动收起系统键盘**（用户
 * 第二轮反馈 3a/3b 的联动设计），让整块屏幕回到 App 手里，剩余的手指才有机会落下。
 *
 * 收起键盘这个动作发生在 Compose UI 层（`clearFocus()` + `hide()`），而判定发生在
 * 手势协程里，两者之间就用本类传话。
 *
 * ## 用法
 *
 * ```kotlin
 * // ① 应用根部创建并 remember（MainActivity）
 * val fiveFingerGate = remember { FiveFingerGate() }
 * CompositionLocalProvider(LocalFiveFingerGate provides fiveFingerGate) { ... }
 *
 * // ② 手势层每轮更新（Modifier.pocketGestures 内部）
 * gate.observeGather(fingerCount = pressed.size, claimed = true/false)
 *
 * // ③ UI 层读取（FusedControlScreen）
 * val yield = LocalFiveFingerGate.current.shouldYieldToFiveFinger
 * ```
 *
 * 所有状态都在主线程上由手势协程 / 组合读写，不涉及并发。
 */
class FiveFingerGate {

    /** 本次触摸是否已被五指挥势层接管（凑指窗口内凑满 5 指）。 */
    var claimed by mutableStateOf(false)
        internal set

    /**
     * 凑指窗口内已观察到的**最大**手指数。
     *
     * 达到 [GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT] 即认为「有五指挥势意图」：
     * 此时 5 指还没凑齐，但已经足够让 UI 层让位（收起系统输入法）。
     */
    var pendingFingers by mutableStateOf(0)
        internal set

    /**
     * UI 层唯一需要读的判据：是否应当让位给五指挥势。
     *
     * - `claimed`：5 指已凑齐，手势必然归五指层，此刻收起键盘 / 停止激活都不会误伤；
     * - `intentDetected`：还在凑，但意图明确。
     */
    val shouldYieldToFiveFinger: Boolean
        get() = claimed || intentDetected

    /**
     * 凑指窗口内是否已观察到「有五指挥势意图」的手指数
     * （≥ [GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT]）。
     *
     * 按键层（`Modifier.pocketKeyGestures`）用它做「作废」判定：兄弟按键节点互相看不见
     * 对方的指针，5 根手指分别落在 5 个不同键帽上时每颗键的本地计数永远是 1，
     * 只有这份全局计数能认出这是五指挥势（详见 KeyPointerInput 的「兄弟盲区」小节）。
     */
    val intentDetected: Boolean
        get() = pendingFingers >= GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT

    /**
     * 手势层调用：汇报凑指窗口内的最新手指数。
     *
     * @param fingerCount 当前按下指针数（含落在 IME 上、我们看不到的那些之外的可见部分）
     * @param claimed 本次触摸是否已凑满 5 指并被五指层接管
     */
    internal fun observeGather(fingerCount: Int, claimed: Boolean) {
        if (fingerCount > pendingFingers) pendingFingers = fingerCount
        this.claimed = claimed
    }

    /** 手势层调用：一次手势结束（所有手指抬起）后复位。 */
    internal fun reset() {
        claimed = false
        pendingFingers = 0
    }
}

/**
 * 应用级的五指意图闸门。
 *
 * 默认给一个**空的独立实例**而不是抛异常：这样没被 `CompositionLocalProvider` 包住的
 * 组合（单元测试里的局部页面、未来的新页面）也能安全求值，只是永远读不到让位信号，
 * 行为退回「不与五指挥势联动」。
 */
val LocalFiveFingerGate: ProvidableCompositionLocal<FiveFingerGate> =
    staticCompositionLocalOf { FiveFingerGate() }

/** 取当前组合的 [FiveFingerGate]（等价于 `LocalFiveFingerGate.current`，语义更清晰）。 */
@Composable
fun rememberFiveFingerGate(): FiveFingerGate = LocalFiveFingerGate.current
