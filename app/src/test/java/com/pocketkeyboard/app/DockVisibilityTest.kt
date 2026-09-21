package com.pocketkeyboard.app

import com.pocketkeyboard.app.ui.AppMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dock 可见性策略纯函数单测：配对页常显；键盘 / 触控板页默认隐藏，
 * 仅单指边缘内滑激活后可见（「切模式后再次自动隐藏」由 MainActivity 的
 * `LaunchedEffect(mode)` 复位激活态实现，不进入本函数）。
 */
class DockVisibilityTest {

    @Test
    fun `pairing page always shows dock`() {
        assertTrue(dockVisibleFor(AppMode.PAIRING, edgeActivated = false))
        assertTrue(dockVisibleFor(AppMode.PAIRING, edgeActivated = true))
    }

    @Test
    fun `keyboard and trackpad pages hide dock by default`() {
        assertFalse(dockVisibleFor(AppMode.KEYBOARD, edgeActivated = false))
        assertFalse(dockVisibleFor(AppMode.TRACKPAD, edgeActivated = false))
        assertFalse(dockVisibleFor(AppMode.NUMPAD, edgeActivated = false))
    }

    @Test
    fun `keyboard and trackpad pages show dock after edge activation`() {
        assertTrue(dockVisibleFor(AppMode.KEYBOARD, edgeActivated = true))
        assertTrue(dockVisibleFor(AppMode.TRACKPAD, edgeActivated = true))
        assertTrue(dockVisibleFor(AppMode.NUMPAD, edgeActivated = true))
    }
}
