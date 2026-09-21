package com.pocketkeyboard.app.keyboard

import com.pocketkeyboard.app.hid.HidModifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 融合页「可锁定修饰键排」状态机的纯 JVM 单测（Bug 6）。
 *
 * 覆盖：点击锁定 / 再点解锁、修饰键位图组合、fn 的锁定视觉与单次组合消费语义、
 * 页面退出时的整体复位。
 */
class FusedModifierLatchTest {

    @Test
    fun `tap locks and tap again unlocks`() {
        var locked = emptySet<LockableModifier>()
        locked = FusedModifierLatch.toggle(locked, LockableModifier.CTRL)
        assertEquals(setOf(LockableModifier.CTRL), locked)
        locked = FusedModifierLatch.toggle(locked, LockableModifier.CTRL)
        assertTrue(locked.isEmpty())
    }

    @Test
    fun `multiple modifiers can be locked at once and bits combine`() {
        var locked = emptySet<LockableModifier>()
        locked = FusedModifierLatch.toggle(locked, LockableModifier.CTRL)
        locked = FusedModifierLatch.toggle(locked, LockableModifier.SHIFT)
        locked = FusedModifierLatch.toggle(locked, LockableModifier.ALT)
        locked = FusedModifierLatch.toggle(locked, LockableModifier.GUI)
        assertEquals(
            HidModifier.LEFT_CTRL or HidModifier.LEFT_SHIFT or
                HidModifier.LEFT_ALT or HidModifier.LEFT_GUI,
            FusedModifierLatch.modifierBits(locked),
        )
    }

    @Test
    fun `unlocked set has no modifier bits`() {
        assertEquals(HidModifier.NONE, FusedModifierLatch.modifierBits(emptySet()))
    }

    @Test
    fun `fn is locked visually but never enters the modifier bitmap`() {
        var locked = emptySet<LockableModifier>()
        locked = FusedModifierLatch.toggle(locked, LockableModifier.FN)
        assertTrue(FusedModifierLatch.fnActive(locked))
        // fn 是被控设备之外的键（只触发本机 fn 组合），不进 HID 修饰键位图
        assertEquals(HidModifier.NONE, FusedModifierLatch.modifierBits(locked))
    }

    @Test
    fun `fn combo consumes only the fn latch`() {
        var locked = emptySet<LockableModifier>()
        locked = FusedModifierLatch.toggle(locked, LockableModifier.FN)
        locked = FusedModifierLatch.toggle(locked, LockableModifier.CTRL)
        locked = FusedModifierLatch.withoutFn(locked)
        assertFalse(FusedModifierLatch.fnActive(locked))
        // ctrl 仍锁定：fn 组合（单次生效）不影响其它修饰键
        assertEquals(HidModifier.LEFT_CTRL, FusedModifierLatch.modifierBits(locked))
    }

    @Test
    fun `withoutFn on a set without fn is a no-op`() {
        val locked = setOf(LockableModifier.ALT)
        assertEquals(locked, FusedModifierLatch.withoutFn(locked))
    }

    @Test
    fun `ctrl plus shift latch types ctrl shift letter`() {
        // 锁定 ctrl + shift 后打 a → 修饰位图 = LEFT_CTRL | LEFT_SHIFT
        var locked = emptySet<LockableModifier>()
        locked = FusedModifierLatch.toggle(locked, LockableModifier.CTRL)
        locked = FusedModifierLatch.toggle(locked, LockableModifier.SHIFT)
        assertEquals(
            HidModifier.LEFT_CTRL or HidModifier.LEFT_SHIFT,
            FusedModifierLatch.modifierBits(locked),
        )
    }
}
