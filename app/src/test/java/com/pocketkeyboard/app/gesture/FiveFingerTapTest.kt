package com.pocketkeyboard.app.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isFiveFingerTap]（五指短按判定）的纯 JVM 单测。
 *
 * 短按 = 5 指凑齐后在窗口内全部抬起、且没有哪根手指离开自己的落点。两个失格条件
 * 各自独立生效：超时（长按 / 歇手）与位移（拖动 / 滑动）都绝不能触发。
 */
class FiveFingerTapTest {

    private val tapMaxMs = GestureConstants.FIVE_FINGER_TAP_MAX_MS
    private val tapSlopPx = 24f

    @Test
    fun `quick still tap fires`() {
        assertTrue(isFiveFingerTap(180L, 5f, tapMaxMs, tapSlopPx))
    }

    @Test
    fun `instant tap fires`() {
        assertTrue(isFiveFingerTap(0L, 0f, tapMaxMs, tapSlopPx))
    }

    @Test
    fun `boundary duration and travel still fire`() {
        assertTrue(isFiveFingerTap(tapMaxMs, tapSlopPx, tapMaxMs, tapSlopPx))
    }

    @Test
    fun `long press does not fire`() {
        assertFalse(isFiveFingerTap(tapMaxMs + 1, 0f, tapMaxMs, tapSlopPx))
    }

    @Test
    fun `resting five fingers for seconds does not fire`() {
        assertFalse(isFiveFingerTap(2_000L, 3f, tapMaxMs, tapSlopPx))
    }

    @Test
    fun `five finger drag does not fire`() {
        assertFalse(isFiveFingerTap(150L, 300f, tapMaxMs, tapSlopPx))
    }

    @Test
    fun `finger travel just beyond slop does not fire`() {
        assertFalse(isFiveFingerTap(150L, tapSlopPx + 0.5f, tapMaxMs, tapSlopPx))
    }

    @Test
    fun `negative elapsed is defensively rejected`() {
        assertFalse(isFiveFingerTap(-1L, 0f, tapMaxMs, tapSlopPx))
    }
}
