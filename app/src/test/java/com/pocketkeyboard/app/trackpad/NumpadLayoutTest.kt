package com.pocketkeyboard.app.trackpad

import com.pocketkeyboard.app.hid.HidUsage
import com.pocketkeyboard.app.keyboard.KeyAction
import com.pocketkeyboard.app.keyboard.KeyStyle
import com.pocketkeyboard.app.keyboard.RowItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数字小键盘布局数据模型的结构校验：4 列 × 6 行、每行 4u、键位与 usage 齐全。
 */
class NumpadLayoutTest {

    /** 取出一行里所有单键的 usage（小键盘没有叠放键槽）。 */
    private fun KeyRow_usages(row: com.pocketkeyboard.app.keyboard.KeyRow): List<Int> =
        row.items.filterIsInstance<RowItem.Key>().map { (it.spec.action as KeyAction.Usage).usage }

    @Test
    fun `小键盘是 4 列 6 行`() {
        val rows = NumpadLayout.rows()

        assertEquals(NumpadLayout.COLUMNS, 4)
        assertEquals("小键盘应有 6 行（需求要求 4 列 5 行以上）", 6, rows.size)
    }

    @Test
    fun `每行宽度都是 4u`() {
        NumpadLayout.rows().forEachIndexed { index, row ->
            assertEquals("第 ${index + 1} 行宽度应为 4u", 4f, row.totalU, 0.001f)
        }
    }

    @Test
    fun `最后一行的回车是通栏 4u`() {
        val last = NumpadLayout.rows().last()
        assertEquals(1, last.items.size)
        val enter = last.items.single() as RowItem.Key
        assertEquals(4f, enter.widthU, 0.001f)
        assertEquals(KeyAction.Usage(HidUsage.KEY_KP_ENTER), enter.spec.action)
        assertEquals(KeyStyle.MODIFIER, enter.spec.style)
    }

    @Test
    fun `键位与 usage 与需求逐行对应`() {
        val rows = NumpadLayout.rows()

        // 行 1–4：7 8 9 / · 4 5 6 × · 1 2 3 − · 0 00 . +
        assertEquals(
            listOf(
                HidUsage.KEY_KP_1 + 6, HidUsage.KEY_KP_1 + 7, HidUsage.KEY_KP_1 + 8,
                HidUsage.KEY_KP_DIVIDE,
            ),
            rows[0].let { KeyRow_usages(it) },
        )
        assertEquals(
            listOf(
                HidUsage.KEY_KP_1 + 3, HidUsage.KEY_KP_1 + 4, HidUsage.KEY_KP_1 + 5,
                HidUsage.KEY_KP_MULTIPLY,
            ),
            rows[1].let { KeyRow_usages(it) },
        )
        assertEquals(
            listOf(
                HidUsage.KEY_KP_1, HidUsage.KEY_KP_1 + 1, HidUsage.KEY_KP_1 + 2,
                HidUsage.KEY_KP_SUBTRACT,
            ),
            rows[2].let { KeyRow_usages(it) },
        )
        assertEquals(
            listOf(
                HidUsage.KEY_KP_0, HidUsage.KEY_KP_00, HidUsage.KEY_KP_DECIMAL,
                HidUsage.KEY_KP_ADD,
            ),
            rows[3].let { KeyRow_usages(it) },
        )
        // 行 5：C（清空 = Esc） % = 退格
        assertEquals(
            listOf(
                HidUsage.KEY_ESCAPE, HidUsage.KEY_KP_PERCENT, HidUsage.KEY_KP_EQUAL,
                HidUsage.KEY_BACKSPACE,
            ),
            rows[4].let { KeyRow_usages(it) },
        )
    }

    @Test
    fun `需求要求的全部按键都存在`() {
        val usages = NumpadLayout.rows().flatMap { KeyRow_usages(it) }
        val labels = NumpadLayout.rows()
            .flatMap { row -> row.items.filterIsInstance<RowItem.Key>() }
            .map { it.spec }

        // 数字 0–9 与 00
        (0..9).forEach { digit ->
            assertTrue("缺少数字键 $digit", usages.contains(NumpadLayout_keypadDigit(digit)))
        }
        assertTrue(usages.contains(HidUsage.KEY_KP_00))

        // 运算符 + − × ÷ = %
        listOf(
            HidUsage.KEY_KP_ADD,
            HidUsage.KEY_KP_SUBTRACT,
            HidUsage.KEY_KP_MULTIPLY,
            HidUsage.KEY_KP_DIVIDE,
            HidUsage.KEY_KP_EQUAL,
            HidUsage.KEY_KP_PERCENT,
        ).forEach { usage ->
            assertTrue("缺少运算符 usage 0x${usage.toString(16)}", usages.contains(usage))
        }

        // 小数点、退格、回车、清空
        assertTrue(usages.contains(HidUsage.KEY_KP_DECIMAL))
        assertTrue(usages.contains(HidUsage.KEY_BACKSPACE))
        assertTrue(usages.contains(HidUsage.KEY_KP_ENTER))
        assertTrue("清空键应映射为 Esc", usages.contains(HidUsage.KEY_ESCAPE))

        // 每个键都有文案资源（文案全部进 strings.xml 的约束）
        assertTrue(labels.all { it.labelRes != 0 })
        // 退格 / 回车 / 清空有无障碍描述
        val described = NumpadLayout.rows()
            .flatMap { row -> row.items.filterIsInstance<RowItem.Key>() }
            .mapNotNull { it.spec.contentDescRes }
        assertTrue("退格 / 回车 / 清空需要无障碍描述", described.size >= 3)
    }

    @Test
    fun `物理按键总数为 21`() {
        val count = NumpadLayout.rows().sumOf { it.keyCount }

        // 4 列 × 5 行 = 20，加最后一行通栏回车 = 21
        assertEquals(21, count)
    }

    /** 与 NumpadLayout 内部实现保持一致的数字 → keypad usage（避免测试依赖私有函数）。 */
    private fun NumpadLayout_keypadDigit(value: Int): Int =
        if (value == 0) HidUsage.KEY_KP_0 else HidUsage.KEY_KP_1 + (value - 1)
}
