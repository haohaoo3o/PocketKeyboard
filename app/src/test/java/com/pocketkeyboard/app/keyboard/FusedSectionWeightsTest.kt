package com.pocketkeyboard.app.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖屏融合页「两个 mode 视觉可区分」的权重单测（B4）。
 *
 * 真机实测第三轮的症状：竖屏下 KEYBOARD 与 TRACKPAD 两种 mode 展示的都是同一个
 * 融合布局，五指收缩 / 张开之后**视觉毫无变化**，用户完全看不出模式切没切。
 * 现在触控板区 / 键盘区的权重随 mode 反转：
 *
 * - 键盘模式（张开 / KEYBOARD / NUMPAD）：键盘区（系统输入法唤起区）权重更大，
 *   触控板区缩小，并自动弹出系统输入法；
 * - 触控板模式（收缩 / TRACKPAD）：触控板区权重更大，键盘区缩成一条（仅留修饰键排），
 *   并收起系统输入法。
 *
 * 本测试锁住纯权重部分（UI / 输入法联动由 AppSmokeTest 的冒烟断言覆盖）。
 */
class FusedSectionWeightsTest {

    @Test
    fun `两个 mode 的触控板与键盘区权重互换`() {
        assertEquals(
            FusedLayout.WEIGHT_TRACKPAD_TRACKPAD_MODE,
            fusedTrackpadWeight(keyboardMode = false),
        )
        assertEquals(
            FusedLayout.WEIGHT_KEYBOARD_AREA_TRACKPAD_MODE,
            fusedKeyboardAreaWeight(keyboardMode = false),
        )
        assertEquals(
            FusedLayout.WEIGHT_TRACKPAD_KEYBOARD_MODE,
            fusedTrackpadWeight(keyboardMode = true),
        )
        assertEquals(
            FusedLayout.WEIGHT_KEYBOARD_AREA_KEYBOARD_MODE,
            fusedKeyboardAreaWeight(keyboardMode = true),
        )
    }

    @Test
    fun `键盘模式键盘区大于触控板区触控板模式反过来`() {
        assertTrue(
            "键盘模式下键盘区必须比触控板区大，否则「键盘模式」不成立",
            fusedKeyboardAreaWeight(true) > fusedTrackpadWeight(true),
        )
        assertTrue(
            "触控板模式下触控板区必须比键盘区大，否则「触控板模式」不成立",
            fusedTrackpadWeight(false) > fusedKeyboardAreaWeight(false),
        )
    }

    @Test
    fun `两个 mode 的分配必须不同`() {
        // 这是「竖屏两个 mode 视觉可区分」的结构性保证
        assertNotEquals(fusedTrackpadWeight(false), fusedTrackpadWeight(true))
        assertNotEquals(fusedKeyboardAreaWeight(false), fusedKeyboardAreaWeight(true))
    }

    @Test
    fun `权重之和为一Column 才能分完剩余高度`() {
        // Column 里两个 weight 与固定高度的修饰键排共同分完剩余高度
        assertEquals(
            1f,
            fusedTrackpadWeight(true) + fusedKeyboardAreaWeight(true),
            1e-4f,
        )
        assertEquals(
            1f,
            fusedTrackpadWeight(false) + fusedKeyboardAreaWeight(false),
            1e-4f,
        )
    }

    // -------------------------------------- IME 可见时的三段紧贴布局（问题 3b）

    @Test
    fun `IME 可见时输入区权重收成零不再制造空隙`() {
        // 真机症状：IME 弹出后「触控板下边缘没有贴着系统键盘」——四段结构里
        // 输入区夹在触控板与修饰键排之间造成空隙。契约：IME 可见时它必须消失
        assertEquals(0f, fusedKeyboardAreaWeight(keyboardMode = true, imeVisible = true))
        assertEquals(0f, fusedKeyboardAreaWeight(keyboardMode = false, imeVisible = true))
    }

    @Test
    fun `IME 可见时触控板吃满全部弹性高度`() {
        assertEquals(1f, fusedTrackpadWeight(keyboardMode = true, imeVisible = true))
        assertEquals(1f, fusedTrackpadWeight(keyboardMode = false, imeVisible = true))
    }

    @Test
    fun `IME 可见时两个权重之和仍为一`() {
        for (keyboardMode in listOf(true, false)) {
            assertEquals(
                1f,
                fusedTrackpadWeight(keyboardMode, imeVisible = true) +
                    fusedKeyboardAreaWeight(keyboardMode, imeVisible = true),
                1e-4f,
            )
        }
    }

    @Test
    fun `IME 隐藏时权重与不传 imeVisible 的旧行为完全一致`() {
        // 默认参数 imeVisible = false 必须退回 B4 的 mode 权重（点按热区不受影响）
        for (keyboardMode in listOf(true, false)) {
            assertEquals(
                fusedTrackpadWeight(keyboardMode),
                fusedTrackpadWeight(keyboardMode, imeVisible = false),
            )
            assertEquals(
                fusedKeyboardAreaWeight(keyboardMode),
                fusedKeyboardAreaWeight(keyboardMode, imeVisible = false),
            )
        }
    }
}
