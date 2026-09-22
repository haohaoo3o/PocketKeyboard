package com.pocketkeyboard.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 五指挥势几何与判定的纯 JVM 单测。
 *
 * 阈值来自 [GestureConstants]：收缩 / 张开 30%、横滑 80dp（真机 440dpi 上折 220px，
 * 约 1/5 屏宽；原来的 200dp 折 550px 超过小米 1080px 屏宽的一半，物理上不可能达到）。
 * 这里按密度 1f 把 80dp 折算成 80px 使用。
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
    fun `掌心多报一根指针时仍然算出几何`() {
        // 真机上做五指挥势经常连掌心 / 掌根一起贴上（系统多报一根指针）。
        // 以前要求「恰好 5 根」，多一根就返回 null → 整个手势被静默丢弃，
        // 表现为「pinch / spread 怎么都不触发」。现在按「至少 5 根」计算
        val sixFingers = line(startX = 0f, step = 10f) + FingerPoint(50f, 100f)
        val geometry = fiveFingerGeometry(sixFingers)
        assertNotNull("6 根指针也必须算得出几何，否则五指手势被误杀", geometry)
        // 质心取 6 点平均：0/10/20/30/40/50 → 25
        assertEquals(25f, geometry!!.centroidX, 0.001f)
    }

    @Test
    fun `六指时收缩比例仍然判得出`() {
        val initial = fiveFingerGeometry(line(0f, 10f) + FingerPoint(50f, 100f))!!
        val pinched = fiveFingerGeometry(line(0f, 7f) + FingerPoint(35f, 100f))!!
        assertEquals(GestureVerdict.Pinch, classifyFiveFingerGesture(initial, pinched, 80f))
    }

    @Test
    fun `间距缩小超过三成判定为收缩`() {
        val initial = fiveFingerGeometry(line(0f, 10f))!!
        // 以质心为中心缩到 0.7 倍：间距 10 -> 7，缩小 30%
        val pinched = fiveFingerGeometry(line(0f, 7f))!!

        val verdict = classifyFiveFingerGesture(initial, pinched, swipeThresholdPx = 80f)

        assertEquals(GestureVerdict.Pinch, verdict)
    }

    @Test
    fun `间距刚好三成边界不算收缩`() {
        val initial = fiveFingerGeometry(line(0f, 10f))!!
        // 缩到 0.7001 倍：缩小 29.99%，未达到 30% 阈值
        val almost = fiveFingerGeometry(line(0f, 7.001f))!!

        val verdict = classifyFiveFingerGesture(initial, almost, swipeThresholdPx = 80f)

        assertEquals(GestureVerdict.None, verdict)
    }

    @Test
    fun `间距放大超过三成判定为张开`() {
        val initial = fiveFingerGeometry(line(0f, 10f))!!
        // 放大到 1.4 倍：间距 10 -> 14，放大 40%
        val spread = fiveFingerGeometry(line(0f, 14f))!!

        val verdict = classifyFiveFingerGesture(initial, spread, swipeThresholdPx = 80f)

        assertEquals(GestureVerdict.Spread, verdict)
    }

    @Test
    fun `质心横移超过 80dp 判定为右滑`() {
        val initial = fiveFingerGeometry(line(0f, 10f))!!
        val moved = fiveFingerGeometry(line(250f, 10f))!!

        val verdict = classifyFiveFingerGesture(initial, moved, swipeThresholdPx = 80f)

        assertEquals(GestureVerdict.Swipe(SwipeDirection.RIGHT), verdict)
    }

    @Test
    fun `质心左移超过 80dp 判定为左滑`() {
        val initial = fiveFingerGeometry(line(500f, 10f))!!
        val moved = fiveFingerGeometry(line(200f, 10f))!!

        val verdict = classifyFiveFingerGesture(initial, moved, swipeThresholdPx = 80f)

        assertEquals(GestureVerdict.Swipe(SwipeDirection.LEFT), verdict)
    }

    @Test
    fun `位移不足阈值时判定为无手势`() {
        val initial = fiveFingerGeometry(line(0f, 10f))!!
        // 60px 抖动：低于 80dp 阈值，不该判成横滑（捏合 / 张开时的附带漂移量级）
        val jitter = fiveFingerGeometry(line(60f, 10f))!!

        val verdict = classifyFiveFingerGesture(initial, jitter, swipeThresholdPx = 80f)

        assertEquals(GestureVerdict.None, verdict)
    }

    @Test
    fun `张开优先于横滑`() {
        // 张开几乎总会伴随质心移动；必须判成张开而不是设备切换
        val initial = fiveFingerGeometry(line(0f, 10f))!!
        val spreadAndMove = fiveFingerGeometry(line(400f, 16f))!!

        val verdict = classifyFiveFingerGesture(initial, spreadAndMove, swipeThresholdPx = 80f)

        assertEquals(GestureVerdict.Spread, verdict)
    }

    @Test
    fun `初始间距过小时忽略收缩判定避免除零`() {
        val degenerate = fiveFingerGeometry(
            (0 until GestureConstants.REQUIRED_FINGER_COUNT).map { FingerPoint(10f, 10f) },
        )!!
        val moved = fiveFingerGeometry(line(300f, 0.01f))!!

        val verdict = classifyFiveFingerGesture(degenerate, moved, swipeThresholdPx = 80f)

        assertTrue("6 指时也应判成横滑", verdict is GestureVerdict.Swipe)
    }
}
