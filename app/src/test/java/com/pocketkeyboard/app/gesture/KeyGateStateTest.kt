package com.pocketkeyboard.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按键「五指防误触门控」状态机的纯 JVM 单测（问题 3b / 第三轮的兄弟盲区修复）。
 *
 * ## 为什么单独测这个
 *
 * `pocketKeyGestures` 以前在**第 1 根手指落下时**就 `onPress()`：用户做一次五指捏合，
 * 落在键帽上的那几颗键会先各打一次给被控设备，然后才被作废（用户的误触反馈）。
 * 现在按下后先不激活，进入 [KeyGateState] 门控状态机，判定与挂起等待被拆开：
 *
 * - **判定**在 [KeyGateState.onEvent] —— 纯函数、零依赖、完全确定性，就在本文件测；
 * - **挂起等待**（`awaitPointerEvent` + `withTimeoutOrNull`）在 `awaitKeyActivation`，
 *   只负责把 [KeyGateAction] 翻译成回调。
 *
 * 这样拆分也让测试不必依赖 Compose 测试时钟，避免与同 JVM 里的其它 Compose 冒烟测试
 * 相互拖累（Robolectric 下多测试类共享进程时，`waitForIdle` 会互相干扰）。
 *
 * ## 输入：本地观察 + 全局闸门
 *
 * 本地三元组 (pressedCount, ourPointerDown, timedOut) + 闸门二元组
 * (pendingFingers, claimed)。后者来自共享闸门 [FiveFingerGate]（父层
 * `Modifier.pocketGestures` 在 Initial pass 写入）——按键节点互相看不见对方的指针，
 * 「5 指分别落在 5 个键帽上」时本地 pressedCount 永远是 1，只有闸门认得出这是五指挥势。
 */
class KeyGateStateTest {

    private fun gate() = KeyGateState(requiredFingerCount = 5)

    /** 一步：喂一次观察，返回动作。 */
    private fun KeyGateState.step(
        pressed: Int,
        ourDown: Boolean = true,
        timedOut: Boolean = false,
        pending: Int = 0,
        claimed: Boolean = false,
    ): KeyGateAction = onEvent(pressed, ourDown, timedOut, pending, claimed)

    @Test
    fun `五根手指一次性落下时一次都不激活`() {
        val gate = gate()
        // 5 指同帧落下：第一次观察就见到 5 根 → 作废
        assertEquals(KeyGateAction.Abandon, gate.step(pressed = 5))
        assertFalse("作废前从未激活", gate.activated)
        assertEquals(KeyGatePhase.DONE, gate.phase)
    }

    @Test
    fun `五根手指在门控期内依次落下时一次都不激活`() {
        val gate = gate()
        assertEquals(KeyGateAction.Wait, gate.step(pressed = 1))
        assertEquals(KeyGateAction.Wait, gate.step(pressed = 2))
        assertEquals(KeyGateAction.Wait, gate.step(pressed = 3))
        assertEquals(KeyGateAction.Wait, gate.step(pressed = 4))
        // 第 5 根落下，门控还没到期（pressGuardMs = 300ms）
        assertEquals(KeyGateAction.Abandon, gate.step(pressed = 5))
        assertFalse("5 指凑齐前不该激活过", gate.activated)
    }

    @Test
    fun `五根手指在激活后才凑齐时立即松键`() {
        val gate = gate()
        // 门控到期才激活（第 5 根迟迟不落下）
        assertEquals(KeyGateAction.Activate, gate.step(pressed = 2, timedOut = true))
        assertTrue(gate.activated)
        // 之后第 5 根落下：立刻松键，不能把被控设备卡在按下的键上
        assertEquals(KeyGateAction.Abandon, gate.step(pressed = 5))
    }

    @Test
    fun `孤立单指轻点零延迟激活并松开`() {
        val gate = gate()
        // 抬起那一刻屏幕上已无其它指针：不等门控，直接激活 + 松开
        assertEquals(
            KeyGateAction.ActivateAndRelease,
            gate.step(pressed = 0, ourDown = false),
        )
        assertTrue(gate.activated)
    }

    @Test
    fun `按住不放的键在门控到期后激活`() {
        val gate = gate()
        assertEquals(KeyGateAction.Wait, gate.step(pressed = 1))
        assertEquals(
            "门控期满仍未凑到 5 指 → 按普通按下处理",
            KeyGateAction.Activate,
            gate.step(pressed = 1, timedOut = true),
        )
        // 已激活：等本指抬起补发松键
        assertEquals(KeyGateAction.Release, gate.step(pressed = 0, ourDown = false))
    }

    @Test
    fun `有人抬起时修饰键抢在字符键之前激活`() {
        val gate = gate()
        assertEquals(KeyGateAction.Wait, gate.step(pressed = 1))   // 本指落下
        assertEquals(KeyGateAction.Wait, gate.step(pressed = 2))   // 第二根落下（和弦）
        assertEquals(
            "第二根抬起 → 峰值下降 → 不可能是五指伸展 → 立刻激活修饰键",
            KeyGateAction.Activate,
            gate.step(pressed = 1),
        )
    }

    @Test
    fun `本指抬起但别人还按着时进入收敛期`() {
        val gate = gate()
        assertEquals(KeyGateAction.Wait, gate.step(pressed = 1))
        assertEquals(KeyGateAction.Wait, gate.step(pressed = 2))
        // 本指抬起、屏幕上还有 1 根：不立刻激活，等那颗先按下的键先生效
        assertEquals(
            KeyGateAction.Wait,
            gate.step(pressed = 1, ourDown = false),
        )
        assertEquals(KeyGatePhase.SETTLE, gate.phase)
        // 收敛期满：安全激活 + 松开（不会让和弦无限等）
        assertEquals(
            KeyGateAction.ActivateAndRelease,
            gate.step(pressed = 1, ourDown = false, timedOut = true),
        )
    }

    @Test
    fun `收敛期内凑齐五指仍然作废`() {
        val gate = gate()
        gate.step(pressed = 1)
        gate.step(pressed = 2)
        assertEquals(KeyGateAction.Wait, gate.step(pressed = 2, ourDown = false))
        assertEquals(KeyGatePhase.SETTLE, gate.phase)
        // 收敛期内第 5 根落下：仍然作废（此时 onPress 从未被调用）
        assertEquals(KeyGateAction.Abandon, gate.step(pressed = 5))
        assertFalse(gate.activated)
    }

    @Test
    fun `六根指针同样判定为五指挥势`() {
        val gate = gate()
        // 掌心 / 掌根贴到屏幕时系统会多报一根指针，同样必须作废
        assertEquals(KeyGateAction.Abandon, gate.step(pressed = 6))
    }

    // ---------------------------------------------------------------- 共享闸门（兄弟盲区）

    /**
     * **兄弟盲区**的核心用例：本键只看到自己的 1 根手指，但父层闸门报的是 5 指。
     *
     * Compose 的 HitPathTracker 按指针各自分发：落在别的键帽 / 触控板上的手指不会出现在
     * 本节点的 `PointerEvent.changes` 里，所以「五指分别落在 5 个键上」时每颗键的本地
     * `pressedCount` 永远是 1。这正是真机上「五指放上键盘仍误触」的根因——本地计数
     * 永远等不到 5。现在作废判定读共享闸门的全局计数，本用例锁住这条路径。
     */
    @Test
    fun `自身一根手指但闸门报五根时同样作废`() {
        val gate = gate()
        assertEquals(
            KeyGateAction.Wait,
            gate.step(pressed = 1, pending = 1),
        )
        // 其余 4 根落在别的键上：本节点一个事件都收不到，但闸门的全局计数在涨
        assertEquals("闸门报 5 指 → 立刻作废", KeyGateAction.Abandon, gate.step(pressed = 1, pending = 5))
        assertFalse("作废前从未激活", gate.activated)
        assertEquals(KeyGatePhase.DONE, gate.phase)
    }

    @Test
    fun `闸门报出三指意图即作废`() {
        val gate = gate()
        // 3 指 = 「有五指挥势意图」（FIVE_FINGER_INTENT_FINGER_COUNT）：此时 5 指还没
        // 凑齐，但已经足够判定「这次触摸不是打字」，按键必须作废
        assertEquals(
            KeyGateAction.Abandon,
            gate.step(pressed = 1, pending = GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT),
        )
        assertFalse(gate.activated)
    }

    @Test
    fun `闸门声明已接管时同样作废`() {
        val gate = gate()
        // claimed = true：五指层已凑满并接管，本键即使只看到 1 根也要作废
        assertEquals(KeyGateAction.Abandon, gate.step(pressed = 1, claimed = true))
    }

    @Test
    fun `双指和弦不被闸门误伤`() {
        val gate = gate()
        // 跨键和弦（另一根手指落在别的键上，本节点看不见它）：闸门只报 2 指，不算意图，
        // 这颗键既不作废也不抢跑——继续等门控，与「没有闸门」时的行为完全一致
        assertEquals(KeyGateAction.Wait, gate.step(pressed = 1, pending = 1))
        assertEquals(KeyGateAction.Wait, gate.step(pressed = 1, pending = 2))
        // 门控期满才激活：闸门的全局计数不参与激活时机判定（否则第 2 根手指落下
        // 就会被误判成「有人抬起、峰值下降」而抢跑，那正是要防的误触）
        assertEquals(
            "双指不算五指挥势意图 → 门控期满才激活",
            KeyGateAction.Activate,
            gate.step(pressed = 1, pending = 2, timedOut = true),
        )
    }

    @Test
    fun `激活之后闸门报出意图也立刻松键`() {
        val gate = gate()
        // 门控到期才激活（其余手指迟迟没落齐）
        assertEquals(KeyGateAction.Activate, gate.step(pressed = 1, timedOut = true))
        assertTrue(gate.activated)
        // 激活后又出现五指挥势意图：立刻松键，别把被控设备卡在按下的键上
        assertEquals(KeyGateAction.Abandon, gate.step(pressed = 1, pending = 5))
    }

    @Test
    fun `结束后忽略后续观察`() {
        val gate = gate()
        assertEquals(KeyGateAction.Abandon, gate.step(pressed = 5))
        assertEquals("DONE 之后任何观察都不该再有动作", KeyGateAction.Wait, gate.step(pressed = 1))
    }

    @Test
    fun `峰值只增不减`() {
        val gate = gate()
        gate.step(pressed = 3)
        gate.step(pressed = 2)
        assertEquals("峰值要留住见证过的最多手指数", 3, gate.peakPointerCount)
    }
}
