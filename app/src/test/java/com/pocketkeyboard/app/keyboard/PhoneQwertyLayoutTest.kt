package com.pocketkeyboard.app.keyboard

import com.pocketkeyboard.app.R
import com.pocketkeyboard.app.hid.HidUsage
import com.pocketkeyboard.app.hid.HidUsageMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 26 键 QWERTY 键位数据模型的结构校验：行数、每行键数与键位、每行 u 总宽、
 * 功能行 usage、以及「字母一律可映射、26 个字母不重不漏」。
 */
class PhoneQwertyLayoutTest {

    @Test
    fun `four rows with gboard style key counts`() {
        val rows = PhoneQwertyLayout.rows()
        assertEquals(4, rows.size)
        // 三行字母 10 / 9 / 7 + 底部功能行 4 键（shift / 退格 / 空格 / 回车）
        assertEquals(10, rows[0].keys.size)
        assertEquals(9, rows[1].keys.size)
        assertEquals(7, rows[2].keys.size)
        assertEquals(4, rows[3].keys.size)
    }

    @Test
    fun `every row totals ten u so key width baseline matches`() {
        val rows = PhoneQwertyLayout.rows()
        rows.forEachIndexed { index, row ->
            assertEquals(
                "第 ${index + 1} 行 u 总宽应为 10",
                PhoneQwertyLayout.ROW_U,
                row.totalU,
                0.001f,
            )
        }
        // 第二 / 第三行用两侧留白把 9 / 7 颗字母居中
        assertEquals(0f, rows[0].sideMarginU, 0.001f)
        assertEquals(0.5f, rows[1].sideMarginU, 0.001f)
        assertEquals(1.5f, rows[2].sideMarginU, 0.001f)
        assertEquals(0f, rows[3].sideMarginU, 0.001f)
    }

    @Test
    fun `letter rows are qwertyuiop asdfghjkl zxcvbnm`() {
        val rows = PhoneQwertyLayout.rows()
        assertEquals("qwertyuiop", rows[0].keys.letters())
        assertEquals("asdfghjkl", rows[1].keys.letters())
        assertEquals("zxcvbnm", rows[2].keys.letters())
    }

    @Test
    fun `all 26 letters present exactly once and mappable to hid usage`() {
        val rows = PhoneQwertyLayout.rows()
        val chars = rows.take(3)
            .flatMap { it.keys }
            .mapNotNull { (it.action as? KeyAction.Character)?.char }
        assertEquals(26, chars.size)
        assertEquals(26, chars.toSet().size)
        chars.forEach { char ->
            // 每个字母都必须能经 HidUsageMapper 映射成 HID usage（发送链路复用既有映射表）
            assertTrue("字母 $char 无法映射 HID usage", HidUsageMapper.usageForChar(char) != null)
        }
    }

    @Test
    fun `function row is shift backspace space and return`() {
        val specs = PhoneQwertyLayout.rows()[3].keys
        assertEquals(
            KeyAction.Modifier(HidUsage.KEY_LEFT_SHIFT),
            specs[0].action,
        )
        assertEquals(KeyAction.Usage(HidUsage.KEY_BACKSPACE), specs[1].action)
        assertEquals(KeyAction.Usage(HidUsage.KEY_SPACE), specs[2].action)
        assertEquals(KeyAction.Usage(HidUsage.KEY_ENTER), specs[3].action)
        // shift / 退格 1.5u、空格 4u、回车 3u
        assertEquals(1.5f, specs[0].widthU, 0.001f)
        assertEquals(1.5f, specs[1].widthU, 0.001f)
        assertEquals(4f, specs[2].widthU, 0.001f)
        assertEquals(3f, specs[3].widthU, 0.001f)
        // shift 用 FN 视觉风格：粘滞 / 按住时整颗键帽高亮（复用 KeyCap 的 fn 高亮通道）
        assertEquals(KeyStyle.FN, specs[0].style)
        // 功能行都有无障碍描述
        assertTrue(specs.all { it.contentDescRes != null })
    }

    @Test
    fun `letter labels point at uppercase key resources`() {
        // 键帽文案全部指向 key_a … key_z（strings.xml 里已改为大写显示），
        // 且与发送层的小写字符解耦：Character 一律小写，由 HidUsageMapper 决定是否需要 Shift
        val letters = PhoneQwertyLayout.rows().take(3)
            .flatMap { row -> row.keys.mapNotNull { it.action as? KeyAction.Character } }
        letters.forEach { action ->
            assertEquals(
                "字母 ${action.char} 的键帽文案资源不对",
                LETTER_LABEL_RES.getValue(action.char),
                labelResOf(action.char),
            )
            // 发送层是小写字符（大写化由 FusedShiftState 在粘滞 / 按住时处理）
            assertEquals(action.char.lowercaseChar(), action.char)
        }
    }

    @Test
    fun `no fn or function keys in 26 key layout`() {
        val actions = PhoneQwertyLayout.rows().flatMap { it.keys }.map { it.action }
        assertTrue(actions.none { it is KeyAction.Fn })
        assertTrue(actions.none { it is KeyAction.FunctionKey })
        // 唯一的修饰键是 shift
        assertEquals(1, actions.filterIsInstance<KeyAction.Modifier>().size)
    }

    private fun List<KeySpec>.letters(): String =
        mapNotNull { (it.action as? KeyAction.Character)?.char }
            .joinToString(separator = "")

    private fun labelResOf(char: Char): Int = LETTER_LABEL_RES.getValue(char)

    private companion object {
        val LETTER_LABEL_RES: Map<Char, Int> = mapOf(
            'q' to R.string.key_q, 'w' to R.string.key_w, 'e' to R.string.key_e,
            'r' to R.string.key_r, 't' to R.string.key_t, 'y' to R.string.key_y,
            'u' to R.string.key_u, 'i' to R.string.key_i, 'o' to R.string.key_o,
            'p' to R.string.key_p, 'a' to R.string.key_a, 's' to R.string.key_s,
            'd' to R.string.key_d, 'f' to R.string.key_f, 'g' to R.string.key_g,
            'h' to R.string.key_h, 'j' to R.string.key_j, 'k' to R.string.key_k,
            'l' to R.string.key_l, 'z' to R.string.key_z, 'x' to R.string.key_x,
            'c' to R.string.key_c, 'v' to R.string.key_v, 'b' to R.string.key_b,
            'n' to R.string.key_n, 'm' to R.string.key_m,
        )
    }
}
