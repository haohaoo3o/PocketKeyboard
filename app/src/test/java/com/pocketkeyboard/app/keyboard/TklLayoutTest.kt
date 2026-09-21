package com.pocketkeyboard.app.keyboard

import com.pocketkeyboard.app.ui.DevicePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 87 键 TKL 键位数据模型的结构校验：行数、每行键数、每行 u 总宽、
 * 以及苹果 / Windows 两套布局的差异点。
 */
class TklLayoutTest {

    @Test
    fun `87 key tkl has six rows with expected key counts`() {
        val rows = TklLayout.rows(DevicePlatform.OTHER)
        assertEquals(6, rows.size)
        // 行 1：Esc + F1–F12 + 三颗系统键；行 2：grave + 10 数字 + - + = + Backspace
        assertEquals(16, rows[0].keyCount)
        assertEquals(14, rows[1].keyCount)
        assertEquals(14, rows[2].keyCount)
        // 行 4：Caps + 11 + Enter；行 5：双 Shift + 10；行 6：6 修改键 + 空格 + 4 方向键
        assertEquals(13, rows[3].keyCount)
        assertEquals(12, rows[4].keyCount)
        assertEquals(11, rows[5].keyCount)
        // 需求逐行枚举的结构共 80 个物理键（标准 87 键另含 6 键编辑簇，需求未列出）
        assertEquals(80, rows.sumOf { it.keyCount })
    }

    @Test
    fun `row widths follow standard tkl u ratios`() {
        val rows = TklLayout.rows(DevicePlatform.OTHER)
        assertEquals(16f, rows[0].totalU, 0.001f) // 16 × 1u
        assertEquals(15f, rows[1].totalU, 0.001f) // 13 × 1u + Backspace 2u
        assertEquals(15f, rows[2].totalU, 0.001f) // Tab 1.5 + 12 + 反斜杠 1.5
        assertEquals(15f, rows[3].totalU, 0.001f) // Caps 1.75 + 11 + Enter 2.25
        assertEquals(15f, rows[4].totalU, 0.001f) // 左 Shift 2.25 + 10 + 右 Shift 2.75
        assertEquals(17.5f, rows[5].totalU, 0.001f) // 6 × 1.25 + 空格 6.25 + 3 × 1.25
    }

    @Test
    fun `bottom row puts space between modifiers and stacks up down arrows`() {
        val rows = TklLayout.rows(DevicePlatform.OTHER)
        val items = rows[5].items
        val spaceIndex = items.indexOfFirst { item ->
            item is RowItem.Key && item.spec.action == KeyAction.Usage(com.pocketkeyboard.app.hid.HidUsage.KEY_SPACE)
        }
        assertTrue("底行应包含空格键", spaceIndex >= 0)
        val stacked = items.filterIsInstance<RowItem.Stacked>()
        assertEquals(1, stacked.size)
        assertEquals(
            listOf(
                KeyAction.Usage(com.pocketkeyboard.app.hid.HidUsage.KEY_UP_ARROW),
                KeyAction.Usage(com.pocketkeyboard.app.hid.HidUsage.KEY_DOWN_ARROW),
            ),
            stacked[0].keys.map { it.action },
        )
    }

    @Test
    fun `f row defaults to consumer media keys and keeps native f usage for fn`() {
        val rows = TklLayout.rows(DevicePlatform.OTHER)
        val fKeys = rows[0].items.filterIsInstance<RowItem.Key>()
            .map { it.spec }
            .filter { it.action is KeyAction.FunctionKey }
        assertEquals(12, fKeys.size)
        fKeys.forEachIndexed { index, spec ->
            val action = spec.action as KeyAction.FunctionKey
            // fn 活跃时发送 F1–F12 本义
            assertEquals(com.pocketkeyboard.app.hid.HidUsage.KEY_F1 + index, action.nativeUsage)
            // 默认行为是 Consumer Page 媒体键（音量 / 播放 / 亮度 / 电源等，0x0030–0x00FF）
            assertTrue(action.consumerUsage in 0x0030..0x00FF)
        }
    }

    @Test
    fun `apple layout shows small f labels and command left of space`() {
        val apple = TklLayout.rows(DevicePlatform.APPLE)
        val fKeys = apple[0].items.filterIsInstance<RowItem.Key>()
            .map { it.spec }
            .filter { it.action is KeyAction.FunctionKey }
        // 苹果布局：F 行媒体图标下方叠小字 f1–f12
        assertTrue(fKeys.all { it.subLabelRes != null })

        val bottom = apple[5].items.filterIsInstance<RowItem.Key>().map { it.spec }
        // 苹果布局空格左侧是 command（LeftGUI）
        val spaceIndex = bottom.indexOfFirst {
            it.action == KeyAction.Usage(com.pocketkeyboard.app.hid.HidUsage.KEY_SPACE)
        }
        assertEquals(
            KeyAction.Modifier(com.pocketkeyboard.app.hid.HidUsage.KEY_LEFT_GUI),
            bottom[spaceIndex - 1].action,
        )
        // 苹果布局存在 fn / Globe 键
        assertTrue(bottom.any { it.action == KeyAction.Fn })
    }

    @Test
    fun `windows layout puts alt and win left of space`() {
        val windows = TklLayout.rows(DevicePlatform.OTHER)
        val bottom = windows[5].items.filterIsInstance<RowItem.Key>().map { it.spec }
        val spaceIndex = bottom.indexOfFirst {
            it.action == KeyAction.Usage(com.pocketkeyboard.app.hid.HidUsage.KEY_SPACE)
        }
        // 空格左侧：ctrl / win / alt
        assertEquals(
            KeyAction.Modifier(com.pocketkeyboard.app.hid.HidUsage.KEY_LEFT_CONTROL),
            bottom[spaceIndex - 3].action,
        )
        assertEquals(
            KeyAction.Modifier(com.pocketkeyboard.app.hid.HidUsage.KEY_LEFT_GUI),
            bottom[spaceIndex - 2].action,
        )
        assertEquals(
            KeyAction.Modifier(com.pocketkeyboard.app.hid.HidUsage.KEY_LEFT_ALT),
            bottom[spaceIndex - 1].action,
        )
        // Windows 布局也有 fn 键
        assertTrue(bottom.any { it.action == KeyAction.Fn })
    }
}
