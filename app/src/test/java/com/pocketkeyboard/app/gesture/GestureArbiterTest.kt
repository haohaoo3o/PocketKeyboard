package com.pocketkeyboard.app.gesture

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GestureArbiter] 仲裁状态机的纯 JVM 单测。 */
class GestureArbiterTest {

    @Test
    fun `初始状态是未裁定`() {
        val arbiter = GestureArbiter()
        assertEquals(GestureOwner.UNDECIDED, arbiter.owner.value)
        assertEquals(false, arbiter.isFiveFingerActive)
    }

    @Test
    fun `五指层声明接管后 awaitDecision 立刻返回五指层`() = runBlocking {
        val arbiter = GestureArbiter()

        arbiter.claimFiveFinger()

        val owner = withTimeout(1_000) { arbiter.awaitDecision() }
        assertEquals(GestureOwner.FIVE_FINGER, owner)
        assertEquals(true, arbiter.isFiveFingerActive)
    }

    @Test
    fun `五指层放弃后 awaitDecision 返回触控板层`() = runBlocking {
        val arbiter = GestureArbiter()

        arbiter.releaseToTrackpad()

        val owner = withTimeout(1_000) { arbiter.awaitDecision() }
        assertEquals(GestureOwner.TRACKPAD, owner)
    }

    @Test
    fun `窗口内无人表态时 awaitDecision 超时兜底为触控板层`() = runBlocking {
        val arbiter = GestureArbiter(arbitrationWindow = GestureConstants.ARBITRATION_WINDOW_MS.milliseconds)

        val owner = withTimeout(5_000) { arbiter.awaitDecision() }

        assertEquals(GestureOwner.TRACKPAD, owner)
    }

    @Test
    fun `reset 之后回到未裁定`() {
        val arbiter = GestureArbiter()
        arbiter.claimFiveFinger()
        arbiter.reset()
        assertEquals(GestureOwner.UNDECIDED, arbiter.owner.value)
    }

    @Test
    fun `onGestureStart 兜底清掉上一轮遗留的归属`() {
        val arbiter = GestureArbiter()
        arbiter.claimFiveFinger()

        arbiter.onGestureStart()

        assertEquals(GestureOwner.UNDECIDED, arbiter.owner.value)
    }

    @Test
    fun `releaseToTrackpad 在已裁定时不会改写结果`() {
        val arbiter = GestureArbiter()
        arbiter.claimFiveFinger()

        arbiter.releaseToTrackpad()

        assertEquals(GestureOwner.FIVE_FINGER, arbiter.owner.value)
    }

    @Test
    fun `仲裁窗口默认取 GestureConstants 的 60ms`() {
        val arbiter = GestureArbiter()
        // 通过「60ms 后超时」间接验证默认窗口时长
        val elapsed = measureElapsedMillis {
            runBlocking { arbiter.awaitDecision() }
        }
        assertTrue("实际耗时 ${elapsed}ms 不在预期区间", elapsed in 50L..400L)
    }
    private fun measureElapsedMillis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000L
    }
}
