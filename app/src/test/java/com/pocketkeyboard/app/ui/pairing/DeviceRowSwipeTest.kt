package com.pocketkeyboard.app.ui.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeviceRowSwipe] 纯函数单测（Bug 3 侧滑几何）。
 *
 * 规则：只允许向左滑露出删除（右滑视为取消夹回 0）；露出过半才算进入可删除态。
 */
class DeviceRowSwipeTest {

    private val reveal = 200f

    @Test
    fun `offset is clamped to the reveal range`() {
        assertEquals(0f, DeviceRowSwipe.clampOffset(120f, reveal), 0f)
        assertEquals(-200f, DeviceRowSwipe.clampOffset(-500f, reveal), 0f)
        assertEquals(-100f, DeviceRowSwipe.clampOffset(-100f, reveal), 0f)
        // 向左滑过露出宽度后停住，不会把行拉出屏幕
        assertEquals(-200f, DeviceRowSwipe.clampOffset(-1000f, reveal), 0f)
    }

    @Test
    fun `reveal requires at least half`() {
        assertFalse(DeviceRowSwipe.shouldReveal(-99f, reveal))
        assertTrue(DeviceRowSwipe.shouldReveal(-100f, reveal))
        assertTrue(DeviceRowSwipe.shouldReveal(-101f, reveal))
        assertTrue(DeviceRowSwipe.shouldReveal(-200f, reveal))
    }

    @Test
    fun `rightward drag never reveals`() {
        assertFalse(DeviceRowSwipe.shouldReveal(0f, reveal))
        assertFalse(DeviceRowSwipe.shouldReveal(50f, reveal))
    }
}
