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

    /**
     * B3 之后五指层可以**晚**表态：≥3 指起凑指窗口不再因位移提前放手，只受
     * [GestureConstants.FINGER_GATHER_MAX_MS]（800ms）总上限约束——一次「慢慢张开」
     * 的手势完全可能在第 5 根手指落下前等上大几百毫秒。仲裁器必须继续等，绝不能因为
     * 等得久就把触摸判给触控板层（那正是「张开 / 收缩没反应」的仲裁层症状）。
     */
    @Test
    fun `三指以上摆指期间晚到的接管仍然胜过安全超时`() = runBlocking {
        val arbiter = GestureArbiter()

        val deferred = async { arbiter.awaitDecision() }
        // 凑指窗口总上限 800ms：第 5 根手指此刻才落下，五指层才表态
        delay(GestureConstants.FINGER_GATHER_MAX_MS)
        arbiter.claimFiveFinger()

        assertEquals(
            GestureOwner.FIVE_FINGER,
            withTimeout(1_000) { deferred.await() },
        )
    }

    /**
     * 与之互补：五指层放手后触控板层必须**立刻**拿到裁决（1–2 指手势零延迟，
     * 这正是 B3 保留「1–2 指位移即放手」的原因）。
     */
    @Test
    fun `五指层放手时 awaitDecision 即刻返回触控板层`() = runBlocking {
        val arbiter = GestureArbiter()

        val elapsed = measureElapsedMillis {
            runBlocking {
                launch {
                    delay(30)
                    arbiter.releaseToTrackpad()
                }
                assertEquals(GestureOwner.TRACKPAD, withTimeout(1_000) { arbiter.awaitDecision() })
            }
        }

        assertTrue("实际耗时 ${elapsed}ms，1–2 指触控板手势不该被凑指窗口拖住", elapsed in 10L..600L)
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
