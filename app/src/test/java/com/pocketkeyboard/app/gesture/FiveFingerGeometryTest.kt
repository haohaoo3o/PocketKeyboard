package com.pocketkeyboard.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 五指挥势几何与判定的纯 JVM 单测。
 *
 * 阈值来自 [GestureConstants]：收缩 / 张开 30%、横滑 200dp。
 * 这里把 200dp 按密度 1f 折算成 200px 使用。
 */
class FiveFingerGeometryTest {

    /** 五根手指沿 x 轴等距排布，间距 10px。 */
    private fun line(startX: Float, step: Float): List<FingerPoint> =
        (0 until GestureConstants.REQUIRED_FINGER_COUNT).map { FingerPoint(startX + it * step, 100f) }

    @Test
    fun `质心与平均间距计算正确`() {
        val geometry = fiveFingerGeometry(line(startX = 0f, step = 10f))!!

        // 5 个点 0/10/20/30/40 的质心是 20
        assertEquals(20f, geometry.centroidX, 0.001f)
        assertEquals(100f, geometry.centroidY, 0.001f)
        // C(5,2)=10 段距离：10+20+30+40 + 10+20+30 + 10+20 + 10 = 200 → 平均 20
        assertEquals(20f, geometry.meanSpacing, 0.001f)
    }

    @Test
    fun `指针数不是 5 时返回 null`() {
        assertNull(fiveFingerGeometry(line(startX = 0f, step = 10f).take(4)))
        assertNull(fiveFingerGeometry(emptyList()))
    }

    @Test
    fun `间距缩小超过三成判定为收缩`() {
        val initial = fiveFingerGeometry(line(0f, 10f))!!
        // 以质心为中心缩到 0.7 倍：间距 10 -> 7，缩小 30%
        val pinched = fiveFingerGeometry(line(0f, 7f))!!

        val verdict = classifyFiveFingerGesture(initial, pinched, swipeThresholdPx = 200f)

        assertEquals(GestureVerdict.Pinch, verdict)
    }

    @Test
    fun `间距刚好三成边界不算收缩`() {
        val initial = fiveFingerGeometry(line(0f, 10f))!!
        // 缩到 0.7001 倍：缩小 29.99%，未达到 30% 阈值
        val almost = fiveFingerGeometry(line(0f, 7.001f))!!

        val verdict = classifyFiveFingerGesture(initial, almost, swipeThresholdPx = 200f)

        assertEquals(GestureVerdict.None, verdict)
    }

    @Test
    fun `间距放大超过三成判定为张开`() {
        val initial = fiveFingerGeometry(line(0f, 10f))!!
        // 放大到 1.4 倍：间距 10 -> 14，放大 40%
        val spread = fiveFingerGeometry(line(0f, 14f))!!

        val verdict = classifyFiveFingerGesture(initial, spread, swipeThresholdPx = 200f)

        assertEquals(GestureVerdict.Spread, verdict)
    }

    @Test
    fun `质心横移超过 200dp 判定为右滑`() {
        val initial = fiveFingerGeometry(line(0f, 10f))!!
        val moved = fiveFingerGeometry(line(250f, 10f))!!

        val verdict = classifyFiveFingerGesture(initial, moved, swipeThresholdPx = 200f)

        assertEquals(GestureVerdict.Swipe(SwipeDirection.RIGHT), verdict)
    }

    @Test
    fun `质心左移超过 200dp 判定为左滑`() {
        val initial = fiveFingerGeometry(line(500f, 10f))!!
        val moved = fiveFingerGeometry(line(200f, 10f))!!

        val verdict = classifyFiveFingerGesture(initial, moved, swipeThresholdPx = 200f)

        assertEquals(GestureVerdict.Swipe(SwipeDirection.LEFT), verdict)
    }

    @Test
    fun `位移不足阈值时判定为无手势`() {
        val initial = fiveFingerGeometry(line(0f, 10f))!!
        val jitter = fiveFingerGeometry(line(120f, 10f))!!

        val verdict = classifyFiveFingerGesture(initial, jitter, swipeThresholdPx = 200f)

        assertEquals(GestureVerdict.None, verdict)
    }

    @Test
    fun `张开优先于横滑`() {
        // 张开几乎总会伴随质心移动；必须判成张开而不是设备切换
        val initial = fiveFingerGeometry(line(0f, 10f))!!
        val spreadAndMove = fiveFingerGeometry(line(400f, 16f))!!

        val verdict = classifyFiveFingerGesture(initial, spreadAndMove, swipeThresholdPx = 200f)

        assertEquals(GestureVerdict.Spread, verdict)
    }

    @Test
    fun `初始间距过小时忽略收缩判定避免除零`() {
        val degenerate = fiveFingerGeometry(
            (0 until GestureConstants.REQUIRED_FINGER_COUNT).map { FingerPoint(10f, 10f) },
        )!!
        val moved = fiveFingerGeometry(line(300f, 0.01f))!!

        val verdict = classifyFiveFingerGesture(degenerate, moved, swipeThresholdPx = 200f)

        assertTrue(verdict is GestureVerdict.Swipe)
    }
}
