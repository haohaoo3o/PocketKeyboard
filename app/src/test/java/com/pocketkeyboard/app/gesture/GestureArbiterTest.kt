package com.pocketkeyboard.app.gesture

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    fun `五指层迟迟不表态时 awaitDecision 不会提前超时`() = runBlocking {
        // 事件驱动仲裁：不再有 60ms 固定窗口，五指层在 200ms 后才表态也必须等得到
        val arbiter = GestureArbiter()

        val deferred = async { arbiter.awaitDecision() }
        delay(200)
        arbiter.claimFiveFinger()

        assertEquals(GestureOwner.FIVE_FINGER, withTimeout(1_000) { deferred.await() })
    }

    @Test
    fun `五指层一放手 awaitDecision 立刻返回不等满超时`() = runBlocking {
        val arbiter = GestureArbiter()

        val elapsed = measureElapsedMillis {
            runBlocking {
                launch {
                    delay(50)
                    arbiter.releaseToTrackpad()
                }
                assertEquals(GestureOwner.TRACKPAD, withTimeout(1_000) { arbiter.awaitDecision() })
            }
        }

        // 50ms 后放手就返回，而不是等满 1500ms 安全超时
        assertTrue("实际耗时 ${elapsed}ms 说明等待了固定窗口", elapsed in 30L..600L)
    }

    @Test
    fun `窗口内无人表态时 awaitDecision 超时兜底为触控板层`() = runBlocking {
        val arbiter = GestureArbiter(safetyTimeout = GestureConstants.ARBITRATION_SAFETY_TIMEOUT_MS.milliseconds)

        val owner = withTimeout(5_000) { arbiter.awaitDecision() }

        assertEquals(GestureOwner.TRACKPAD, owner)
    }

    @Test
    fun `安全超时默认取 GestureConstants 的 1500ms`() = runBlocking {
        val arbiter = GestureArbiter()

        val elapsed = measureElapsedMillis {
            runBlocking { arbiter.awaitDecision() }
        }

        assertEquals(GestureConstants.ARBITRATION_SAFETY_TIMEOUT_MS, arbiter.safetyTimeoutMs)
        assertTrue("实际耗时 ${elapsed}ms 不在预期区间", elapsed in 1_400L..2_000L)
    }

    @Test
    fun `安全超时可以调短`() = runBlocking {
        val arbiter = GestureArbiter(safetyTimeout = 100.milliseconds)

        val elapsed = measureElapsedMillis {
            runBlocking { arbiter.awaitDecision() }
        }

        assertTrue("实际耗时 ${elapsed}ms 不在预期区间", elapsed in 80L..600L)
        assertEquals(100L, arbiter.safetyTimeoutMs)
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

    private fun measureElapsedMillis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000L
    }
}
