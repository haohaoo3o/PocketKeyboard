package com.pocketkeyboard.app.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 26 键键盘 shift 状态机（输入法惯例）的纯 JVM 单测：
 * 轻点粘滞一次、按住组合大写、再点取消、五指挥势作废复位。
 */
class FusedShiftStateTest {

    @Test
    fun `tap shift latches one shot and next letter goes uppercase`() {
        val state = FusedShiftState()
        assertFalse(state.active)

        // 轻点 shift：抬起时翻转粘滞
        state.onShiftDown()
        assertTrue(state.active)
        assertTrue(state.onShiftUp())
        assertTrue(state.active)

        // 粘滞态下按字母：发送大写，并立刻清除粘滞（单次生效）
        assertEquals('A', state.charToSend('a'))
        assertFalse(state.active)

        // 之后的字母恢复小写
        assertEquals('b', state.charToSend('b'))
    }

    @Test
    fun `holding shift and pressing a letter does not latch`() {
        val state = FusedShiftState()
        state.onShiftDown()
        // 按住期间按过字母 → 本次是组合，抬手后不粘滞
        assertEquals('A', state.charToSend('a'))
        assertFalse(state.onShiftUp())
        assertFalse(state.active)
        // 抬手后字母恢复小写
        assertEquals('b', state.charToSend('b'))
    }

    @Test
    fun `tapping shift again while latched unlatches`() {
        val state = FusedShiftState()
        state.onShiftDown()
        assertTrue(state.onShiftUp())
        assertTrue(state.latchedNow)

        // 粘滞态再轻点 shift → 取消粘滞
        state.onShiftDown()
        assertFalse(state.onShiftUp())
        assertFalse(state.latchedNow)
        assertFalse(state.active)
    }

    @Test
    fun `latched shift only affects the next letter`() {
        val state = FusedShiftState()
        state.onShiftDown()
        state.onShiftUp()

        assertEquals('Z', state.charToSend('z'))
        // 一次组合用完即清：第二颗字母不再大写
        assertEquals('x', state.charToSend('x'))
    }

    @Test
    fun `reset clears held latched and combo state`() {
        val state = FusedShiftState()
        state.onShiftDown()
        state.charToSend('a')
        state.reset()
        assertFalse(state.active)
        assertFalse(state.latchedNow)
        // 复位后正常小写；抬起也不再翻转粘滞
        assertEquals('q', state.charToSend('q'))
        assertFalse(state.onShiftUp())
    }

    @Test
    fun `holding shift across multiple letters keeps them uppercase`() {
        val state = FusedShiftState()
        state.onShiftDown()
        assertEquals('A', state.charToSend('a'))
        assertEquals('B', state.charToSend('b'))
        // 组合用过 → 抬手不粘滞
        assertFalse(state.onShiftUp())
        assertEquals('c', state.charToSend('c'))
    }
}
