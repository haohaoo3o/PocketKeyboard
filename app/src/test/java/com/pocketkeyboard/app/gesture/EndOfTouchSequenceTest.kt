package com.pocketkeyboard.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [endOfTouchSequence]（触摸序列结束的强制复位策略）的纯 JVM 单测。
 *
 * 策略的取舍背景见被测函数的 KDoc：结束时**不能**一律 `arbiter.reset()`——
 * `releaseToTrackpad()` 刚写下的 TRACKPAD 若被同一批代码立刻清回 UNDECIDED，
 * 触控板层的 `StateFlow.first { }` 可能被 conflation 吞掉裁决（症状 2 的卡死来源）。
 */
class EndOfTouchSequenceTest {

    @Test
    fun `闸门有任何让位态时序列结束必须清零`() {
        val arbiter = GestureArbiter()
        val gate = FiveFingerGate()
        gate.observeGather(fingerCount = 5, claimed = true)

        endOfTouchSequence(arbiter, gate)

        assertFalse(gate.shouldYieldToFiveFinger)
        assertEquals(0, gate.pendingFingers)
        assertFalse(gate.claimed)
    }

    @Test
    fun `五指层陈旧接管声明在序列结束时被清掉`() {
        val arbiter = GestureArbiter()
        arbiter.claimFiveFinger()

        endOfTouchSequence(arbiter, null)

        //触控板层在 claim 那一刻已经拿到过 FIVE_FINGER，清掉只防陈旧声明带进下一轮
        assertEquals(GestureOwner.UNDECIDED, arbiter.owner.value)
        assertFalse(arbiter.isFiveFingerActive)
    }

    @Test
    fun `未裁定时序列结束交还触控板层解挂等待者`() {
        // 五指层没来得及表态序列就结束了（异常退出 / 协程被取消）：还停在
        // awaitGestureOwner 里的触控板层应当立刻拿到 TRACKPAD，而不是熬满 1.5s 超时
        val arbiter = GestureArbiter()

        endOfTouchSequence(arbiter, null)

        assertEquals(GestureOwner.TRACKPAD, arbiter.owner.value)
    }

    @Test
    fun `已放手给触控板层时保持不动不清回未裁定`() {
        // releaseToTrackpad 刚写下的裁决可能还没被触控板层观察到：立刻 reset 会被
        // StateFlow conflation 吞掉 → 触控板层等满安全超时（禁止的行为）
        val arbiter = GestureArbiter()
        arbiter.releaseToTrackpad()

        endOfTouchSequence(arbiter, null)

        assertEquals(GestureOwner.TRACKPAD, arbiter.owner.value)
    }

    @Test
    fun `复位后下一轮手势开始的兜底仍然可用`() {
        // TRACKPAD 终态由触控板层的 onGestureStart 清掉——链路必须仍然闭合
        val arbiter = GestureArbiter()
        arbiter.releaseToTrackpad()
        endOfTouchSequence(arbiter, null)

        arbiter.onGestureStart()

        assertEquals(GestureOwner.UNDECIDED, arbiter.owner.value)
        assertTrue(!arbiter.isFiveFingerActive)
    }
}
