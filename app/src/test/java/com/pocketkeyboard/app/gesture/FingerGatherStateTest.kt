package com.pocketkeyboard.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凑指窗口判定状态机的纯 JVM 单测（B3：位移放手只适用于 1–2 指）。
 *
 * ## 为什么单独测这个
 *
 * 真机实测第三轮的症状是「触控板页做张开 / 收缩没反应」。根因在凑指窗口的放手规则：
 * 「已按下手指位移超过 `FINGER_GATHER_MOVE_SLOP` 且没有新手指落下就放手」这条规则对
 * **张开 / 捏合**是错杀——用户的手指本来就是边落边张开的，第 3、4 根落下的同时先落下
 * 那几根正在向外扩，位移分分钟超过阈值。一旦 ≥3 指就据此放手，五指层把触摸交还给
 * 触控板层，跟踪阶段根本进不去，Spread / Pinch 回调永远不触发。
 *
 * 修法（与本测试锁住的行为一一对应）：
 *
 * - **1–2 指**位移超阈值 → 立刻放手（单指拖动 / 双指滚动是明确的触控板手势，零延迟）；
 * - **≥3 指**（[GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT] 意图已确立）→
 *   不再因位移放手，只受 [GestureConstants.FINGER_GATHER_MAX_MS] 总窗口上限约束。
 *
 * 时间不读系统时钟：由 `nowUptime` 喂进来，测试里可以用任意时间序列驱动。
 */
class FingerGatherStateTest {

    /** 位移阈值（px，对应真机 24dp 的量级）。 */
    private val slop = 24f

    private fun state(
        firstDownUptime: Long = 0L,
        firstFrame: List<GatherFinger>,
    ) = FingerGatherState(
        firstDownUptime = firstDownUptime,
        firstFrame = firstFrame,
        moveSlopPx = slop,
    )

    /** 一根手指：[id] 不变、位置可变；uptime 取最后活动时刻。 */
    private fun finger(id: Long, x: Float, y: Float = 100f, uptime: Long = 0L) =
        GatherFinger(pointerId = id, x = x, y = y, uptimeMillis = uptime)

    /** 断言结论是「继续等」，并返回它以便继续检查截止时间。 */
    private fun GatherVerdict.waiting(): GatherVerdict.Waiting {
        assertTrue("期望继续等，实际是 $this", this is GatherVerdict.Waiting)
        return this as GatherVerdict.Waiting
    }

    // ------------------------------------------------------------ B3 主用例

    /**
     * **3–5 指持续位移仍保持 gathering**：张开手势的手指边落边扩，每根都位移几百 px，
     * 也绝不能被「位移超阈值」劝退。
     */
    @Test
    fun `三指以上持续位移仍然保持 gathering`() {
        val state = state(firstFrame = listOf(finger(1, x = 540f, uptime = 0L)))

        // 第 2 根落下（此时还没到意图阈值）
        var verdict = state.observe(
            pressed = listOf(finger(1, x = 500f, uptime = 10L), finger(2, x = 400f, uptime = 10L)),
            nowUptime = 10L,
        ).waiting()
        assertFalse("两指还不算意图", verdict.intentEstablished)

        // 第 3 根落下 → 意图确立；随后每根都持续向外张开（位移远超 24px 阈值）
        var now = 10L
        val spreadSteps = listOf(
            listOf(finger(1, x = 480f, uptime = 20L), finger(2, x = 380f, uptime = 20L), finger(3, x = 180f, uptime = 20L)),
            listOf(finger(1, x = 460f, uptime = 40L), finger(2, x = 360f, uptime = 40L), finger(3, x = 160f, uptime = 40L)),
            listOf(finger(1, x = 440f, uptime = 60L), finger(2, x = 340f, uptime = 60L), finger(3, x = 140f, uptime = 60L)),
            listOf(finger(1, x = 420f, uptime = 80L), finger(2, x = 320f, uptime = 80L), finger(3, x = 120f, uptime = 80L)),
        )
        for ((index, pressed) in spreadSteps.withIndex()) {
            now += 20L
            verdict = state.observe(pressed = pressed, nowUptime = now).waiting()
            assertTrue("第 $index 次张开位移后意图必须仍然成立", verdict.intentEstablished)
        }

        // 第 4、第 5 根陆续落下（中途仍然在扩）：凑满即判定 Gathered
        verdict = state.observe(
            pressed = spreadSteps.last() + listOf(finger(4, x = 620f, uptime = now + 20L)),
            nowUptime = now + 20L,
        ).waiting()
        now += 20L
        val gathered = state.observe(
            pressed = spreadSteps.last() + listOf(
                finger(4, x = 640f, uptime = now + 20L),
                finger(5, x = 860f, uptime = now + 20L),
            ),
            nowUptime = now + 20L,
        )
        assertTrue("五指凑满必须进入跟踪阶段，实际是 $gathered", gathered is GatherVerdict.Gathered)
        assertEquals(5, (gathered as GatherVerdict.Gathered).fingerCount)
    }

    /** 3 指起只受总窗口上限约束：续期窗口不再宽限，到期就放手。 */
    @Test
    fun `三指以上不再因续期窗口到期放手只受总上限约束`() {
        val state = state(firstFrame = listOf(finger(1, x = 540f, uptime = 0L)))

        // 第 2、第 3 根在 500ms 时才落下：续期窗口（300ms）早就过了
        state.observe(
            pressed = listOf(finger(1, x = 540f, uptime = 0L), finger(2, x = 400f, uptime = 500L)),
            nowUptime = 500L,
        ).waiting()
        val waiting = state.observe(
            pressed = listOf(
                finger(1, x = 540f, uptime = 0L),
                finger(2, x = 400f, uptime = 500L),
                finger(3, x = 200f, uptime = 520L),
            ),
            nowUptime = 520L,
        ).waiting()
        assertTrue(waiting.intentEstablished)
        // 截止时间必须是「第一根手指 + 总上限」，而不是「最近一根新手指 + 续期」
        assertEquals(GestureConstants.FINGER_GATHER_MAX_MS, waiting.deadlineUptime)

        // 总上限内仍然在等（哪怕距离上一根新手指已经过去 300ms 以上）
        state.observe(
            pressed = listOf(
                finger(1, x = 500f, uptime = 700L),
                finger(2, x = 360f, uptime = 700L),
                finger(3, x = 160f, uptime = 700L),
            ),
            nowUptime = 700L,
        ).waiting()

        // 超过总上限（800ms）仍不足 5 指 → 放手
        val expired = state.observe(
            pressed = listOf(
                finger(1, x = 460f, uptime = 900L),
                finger(2, x = 320f, uptime = 900L),
                finger(3, x = 120f, uptime = 900L),
            ),
            nowUptime = 900L,
        )
        assertEquals(
            GatherVerdict.Release(GatherReleaseReason.WINDOW_EXPIRED),
            expired,
        )
    }

    /** 1–2 指是明确的触控板手势：按下即拖动必须零延迟放手。 */
    @Test
    fun `两指按下后位移超阈值立刻放手给触控板层`() {
        val state = state(firstFrame = listOf(finger(1, x = 540f, uptime = 0L)))
        state.observe(
            pressed = listOf(finger(1, x = 540f, uptime = 0L), finger(2, x = 400f, uptime = 10L)),
            nowUptime = 10L,
        ).waiting()

        // 没有新手指落下、而两根都在动（位移 60px > 24px 阈值）→ 立刻放手
        val verdict = state.observe(
            pressed = listOf(finger(1, x = 480f, uptime = 60L), finger(2, x = 400f, uptime = 60L)),
            nowUptime = 60L,
        )
        assertEquals(
            GatherVerdict.Release(GatherReleaseReason.FINGERS_MOVING),
            verdict,
        )
    }

    @Test
    fun `单指拖动位移超阈值立刻放手给触控板层`() {
        val state = state(firstFrame = listOf(finger(1, x = 540f, uptime = 0L)))
        state.observe(pressed = listOf(finger(1, x = 540f, uptime = 0L)), nowUptime = 0L).waiting()

        val verdict = state.observe(pressed = listOf(finger(1, x = 700f, uptime = 30L)), nowUptime = 30L)
        assertEquals(
            GatherVerdict.Release(GatherReleaseReason.FINGERS_MOVING),
            verdict,
        )
    }

    /** 新手指落下会重设位移基准：慢慢把 5 根手指摆开时先落下那几根的漂移不算拖动。 */
    @Test
    fun `新手指落下重设位移基准不再误判成拖动`() {
        val state = state(firstFrame = listOf(finger(1, x = 540f, uptime = 0L)))

        // 第 2 根落下：基准重设到两根当前位置
        state.observe(
            pressed = listOf(finger(1, x = 560f, uptime = 20L), finger(2, x = 400f, uptime = 20L)),
            nowUptime = 20L,
        ).waiting()
        // 两根各自只挪了 5px（摆位漂移，远小于 24px 阈值）→ 继续等
        state.observe(
            pressed = listOf(finger(1, x = 564f, uptime = 40L), finger(2, x = 395f, uptime = 40L)),
            nowUptime = 40L,
        ).waiting()
        // 第 3 根落下：基准再次整体重设
        val verdict = state.observe(
            pressed = listOf(
                finger(1, x = 564f, uptime = 60L),
                finger(2, x = 395f, uptime = 60L),
                finger(3, x = 200f, uptime = 60L),
            ),
            nowUptime = 60L,
        ).waiting()
        assertTrue(verdict.intentEstablished)
    }

    /** 窗口内所有手指抬起：立刻放手，否则触控板层等不到裁决、这次轻点会丢。 */
    @Test
    fun `所有手指抬起立刻放手`() {
        val state = state(firstFrame = listOf(finger(1, x = 540f, uptime = 0L)))
        val verdict = state.observe(pressed = emptyList(), nowUptime = 10L)
        assertEquals(GatherVerdict.Release(GatherReleaseReason.ALL_FINGERS_UP), verdict)
    }

    /** 1–2 指时的续期窗口到期（300ms 无新手指）→ 放手。 */
    @Test
    fun `两指以下按续期窗口到期放手`() {
        val state = state(firstFrame = listOf(finger(1, x = 540f, uptime = 0L)))
        state.observe(
            pressed = listOf(finger(1, x = 540f, uptime = 0L), finger(2, x = 400f, uptime = 100L)),
            nowUptime = 100L,
        ).waiting()

        val verdict = state.observe(
            pressed = listOf(finger(1, x = 540f, uptime = 100L), finger(2, x = 400f, uptime = 100L)),
            nowUptime = 401L,
        )
        assertEquals(
            GatherVerdict.Release(GatherReleaseReason.WINDOW_EXPIRED),
            verdict,
        )
    }

    /** 五指同帧落下（最快的情况）：第一次观察就 Gathered。 */
    @Test
    fun `五指同帧落下立即判定凑满`() {
        val state = state(firstFrame = listOf(finger(1, x = 100f, uptime = 0L)))
        val verdict = state.observe(
            pressed = listOf(
                finger(1, x = 100f, uptime = 0L),
                finger(2, x = 300f, uptime = 0L),
                finger(3, x = 540f, uptime = 0L),
                finger(4, x = 780f, uptime = 0L),
                finger(5, x = 980f, uptime = 0L),
            ),
            nowUptime = 0L,
        )
        assertEquals(GatherVerdict.Gathered(fingerCount = 5), verdict)
    }

    /** 掌心多报一根指针时同样算凑满（≥5 而不是恰好 5）。 */
    @Test
    fun `六根指针同样判定凑满`() {
        val state = state(firstFrame = listOf(finger(1, x = 100f, uptime = 0L)))
        val verdict = state.observe(
            pressed = (1L..6L).map { finger(it, x = it * 180f, uptime = 0L) },
            nowUptime = 0L,
        )
        assertEquals(GatherVerdict.Gathered(fingerCount = 6), verdict)
    }
}
