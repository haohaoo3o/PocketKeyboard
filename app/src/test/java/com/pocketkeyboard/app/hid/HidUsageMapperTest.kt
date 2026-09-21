package com.pocketkeyboard.app.hid

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HidUsageMapper] 单元测试。
 *
 * 只用到 `KeyEvent` 的编译期常量（会被内联），不触发任何 Android 运行时调用。
 */
class HidUsageMapperTest {

    // ---------------------------------------------------------------- 87 键 TKL 覆盖

    @Test
    fun `all letters map to keyboard page usages`() {
        val keyCodes = listOf(
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H,
            KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L,
            KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P,
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_T,
            KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_Z,
        )
        keyCodes.forEachIndexed { index, keyCode ->
            assertEquals(HidUsage.KEY_A + index, HidUsageMapper.usageForKeyCode(keyCode))
        }
    }

    @Test
    fun `digits and symbol keys map correctly`() {
        assertEquals(HidUsage.KEY_1, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_1))
        assertEquals(HidUsage.KEY_0, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_0))
        assertEquals(HidUsage.KEY_MINUS, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_MINUS))
        assertEquals(HidUsage.KEY_EQUAL, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_EQUALS))
        assertEquals(
            HidUsage.KEY_LEFT_BRACKET,
            HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_LEFT_BRACKET),
        )
        assertEquals(
            HidUsage.KEY_RIGHT_BRACKET,
            HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_RIGHT_BRACKET),
        )
        assertEquals(
            HidUsage.KEY_BACKSLASH,
            HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_BACKSLASH),
        )
        assertEquals(HidUsage.KEY_SEMICOLON, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_SEMICOLON))
        assertEquals(HidUsage.KEY_APOSTROPHE, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_APOSTROPHE))
        assertEquals(HidUsage.KEY_GRAVE, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_GRAVE))
        assertEquals(HidUsage.KEY_COMMA, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_COMMA))
        assertEquals(HidUsage.KEY_PERIOD, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_PERIOD))
        assertEquals(HidUsage.KEY_SLASH, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_SLASH))
    }

    @Test
    fun `function row f1 to f12 map to 0x3a to 0x45`() {
        val f1 = KeyEvent.KEYCODE_F1
        for (offset in 0..11) {
            assertEquals(HidUsage.KEY_F1 + offset, HidUsageMapper.usageForKeyCode(f1 + offset))
        }
        assertEquals(0x3A, HidUsage.KEY_F1)
        assertEquals(0x45, HidUsage.KEY_F12)
    }

    @Test
    fun `system keys print screen scroll lock pause map correctly`() {
        assertEquals(HidUsage.KEY_PRINT_SCREEN, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_SYSRQ))
        assertEquals(HidUsage.KEY_SCROLL_LOCK, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_SCROLL_LOCK))
        assertEquals(HidUsage.KEY_PAUSE, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_BREAK))
    }

    @Test
    fun `editing and navigation keys map correctly`() {
        assertEquals(HidUsage.KEY_ESCAPE, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(HidUsage.KEY_TAB, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_TAB))
        assertEquals(HidUsage.KEY_CAPS_LOCK, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_CAPS_LOCK))
        assertEquals(HidUsage.KEY_SPACE, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_SPACE))
        assertEquals(HidUsage.KEY_ENTER, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_ENTER))
        assertEquals(HidUsage.KEY_BACKSPACE, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_DEL))
        assertEquals(HidUsage.KEY_DELETE_FORWARD, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(HidUsage.KEY_INSERT, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_INSERT))
        assertEquals(HidUsage.KEY_HOME, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals(HidUsage.KEY_END, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_MOVE_END))
        assertEquals(HidUsage.KEY_PAGE_UP, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(HidUsage.KEY_PAGE_DOWN, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(HidUsage.KEY_APPLICATION, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_MENU))
    }

    @Test
    fun `arrow keys map to 0x4f to 0x52`() {
        assertEquals(HidUsage.KEY_UP_ARROW, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(HidUsage.KEY_DOWN_ARROW, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(HidUsage.KEY_LEFT_ARROW, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(HidUsage.KEY_RIGHT_ARROW, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun `modifier keys map to 0xe0 to 0xe7 and are recognized as modifiers`() {
        assertEquals(HidUsage.KEY_LEFT_CONTROL, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(HidUsage.KEY_RIGHT_CONTROL, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertEquals(HidUsage.KEY_LEFT_SHIFT, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(HidUsage.KEY_RIGHT_SHIFT, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals(HidUsage.KEY_LEFT_ALT, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals(HidUsage.KEY_RIGHT_ALT, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_ALT_RIGHT))
        assertEquals(HidUsage.KEY_LEFT_GUI, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_META_LEFT))
        assertEquals(HidUsage.KEY_RIGHT_GUI, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_META_RIGHT))

        assertEquals(HidUsage.KEY_LEFT_GUI, HidUsageMapper.modifierUsageFor(KeyEvent.KEYCODE_META_LEFT))
        assertNull(HidUsageMapper.modifierUsageFor(KeyEvent.KEYCODE_A))
    }

    @Test
    fun `numpad keys map to keypad usages`() {
        assertEquals(HidUsage.KEY_KP_0, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_NUMPAD_0))
        assertEquals(HidUsage.KEY_KP_1, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_NUMPAD_1))
        assertEquals(HidUsage.KEY_KP_9, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_NUMPAD_9))
        assertEquals(HidUsage.KEY_KP_DECIMAL, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_NUMPAD_DOT))
        assertEquals(HidUsage.KEY_KP_DIVIDE, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_NUMPAD_DIVIDE))
        assertEquals(HidUsage.KEY_KP_MULTIPLY, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_NUMPAD_MULTIPLY))
        assertEquals(HidUsage.KEY_KP_SUBTRACT, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_NUMPAD_SUBTRACT))
        assertEquals(HidUsage.KEY_KP_ADD, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_NUMPAD_ADD))
        assertEquals(HidUsage.KEY_KP_EQUAL, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_NUMPAD_EQUALS))
        assertEquals(HidUsage.KEY_KP_ENTER, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(HidUsage.KEY_KP_COMMA, HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_NUMPAD_COMMA))
    }

    @Test
    fun `numpad extended keys 00 000 percent parentheses are mapped`() {
        assertEquals(HidUsage.KEY_KP_00, HidUsageMapper.usageForKeyName("00"))
        assertEquals(HidUsage.KEY_KP_000, HidUsageMapper.usageForKeyName("000"))
        assertEquals(HidUsage.KEY_KP_PERCENT, HidUsageMapper.usageForKeyName("%"))
        assertEquals(HidUsage.KEY_KP_LEFT_PARENTHESIS, HidUsageMapper.usageForKeyName("("))
        assertEquals(HidUsage.KEY_KP_RIGHT_PARENTHESIS, HidUsageMapper.usageForKeyName(")"))
        assertEquals(HidUsage.KEY_KP_PLUS_MINUS, HidUsageMapper.usageForKeyName("+/-"))
        assertNull(HidUsageMapper.usageForKeyName("不存在的键"))
    }

    @Test
    fun `system navigation keys are intentionally unmapped`() {
        assertNull(HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_BACK))
        assertNull(HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_HOME))
        assertNull(HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_APP_SWITCH))
        assertNull(HidUsageMapper.usageForKeyCode(KeyEvent.KEYCODE_UNKNOWN))
    }

    @Test
    fun `every mapped usage stays inside descriptor key array range`() {
        val max = HidReportFactory.MAX_KEY_USAGE
        // 修饰键（0xE0–0xE7）不走键阵列：它们在 boot 键盘报告里占第 1 字节的位图，
        // 描述符里由 Usage Minimum/Maximum (LeftControl..RightGUI) + 8 bit 声明，
        // 因此键阵列范围（0x04..MAX_KEY_USAGE）的检查要把它们排除
        HidUsageMapper.KEYCODE_TO_USAGE.values
            .filterNot { HidModifier.isModifier(it) }
            .forEach { usage ->
                assertTrue("usage 0x${usage.toString(16)} out of range", usage in 0x04..max)
            }
        // 反过来：修饰键必须一个不少地都在映射表里（位图才能覆盖左右两侧）
        HidModifier.USAGE_TO_BIT.keys.forEach { modifier ->
            assertTrue(
                "modifier 0x${modifier.toString(16)} missing from mapper",
                HidUsageMapper.KEYCODE_TO_USAGE.containsValue(modifier),
            )
        }
    }

    // ---------------------------------------------------------------- 字符映射

    @Test
    fun `lowercase letters need no shift`() {
        val mapping = HidUsageMapper.usageForChar('a')
        assertNotNull(mapping)
        assertEquals(HidUsage.KEY_A, mapping!!.usage)
        assertFalse(mapping.needsShift)
    }

    @Test
    fun `uppercase letters map to same usage with shift`() {
        val lower = HidUsageMapper.usageForChar('a')!!
        val upper = HidUsageMapper.usageForChar('A')!!
        assertEquals(lower.usage, upper.usage)
        assertTrue(upper.needsShift)
    }

    @Test
    fun `shifted symbol characters are mapped`() {
        val cases = mapOf(
            '!' to HidUsage.KEY_1,
            '@' to 0x1F,
            '#' to 0x20,
            '$' to 0x21,
            '%' to 0x22,
            '^' to 0x23,
            '&' to 0x24,
            '*' to 0x25,
            '(' to 0x26,
            ')' to HidUsage.KEY_0,
            '_' to HidUsage.KEY_MINUS,
            '+' to HidUsage.KEY_EQUAL,
            '{' to HidUsage.KEY_LEFT_BRACKET,
            '}' to HidUsage.KEY_RIGHT_BRACKET,
            '|' to HidUsage.KEY_BACKSLASH,
            ':' to HidUsage.KEY_SEMICOLON,
            '"' to HidUsage.KEY_APOSTROPHE,
            '~' to HidUsage.KEY_GRAVE,
            '<' to HidUsage.KEY_COMMA,
            '>' to HidUsage.KEY_PERIOD,
            '?' to HidUsage.KEY_SLASH,
        )
        cases.forEach { (char, expected) ->
            val mapping = HidUsageMapper.usageForChar(char)
            assertNotNull("char $char should be mapped", mapping)
            assertEquals(expected, mapping!!.usage)
            assertTrue("char $char needs shift", mapping.needsShift)
        }
    }

    @Test
    fun `unmappable characters return null`() {
        assertNull(HidUsageMapper.usageForChar('中'))
        assertNull(HidUsageMapper.usageForChar('\uD83C'))
        assertFalse(HidUsageMapper.isMappable('中'))
    }

    @Test
    fun `reportsForChar produces press and release pair`() {
        val pair = HidUsageMapper.reportsForChar('A')
        assertNotNull(pair)
        val (press, release) = pair!!
        assertEquals(8, press.size)
        assertEquals(HidModifier.LEFT_SHIFT.toByte(), press[0])
        assertEquals(HidUsage.KEY_A.toByte(), press[2])
        assertEquals(0, release[0].toInt())
        assertEquals(0, release[2].toInt())
    }

    // ---------------------------------------------------------------- 修饰键位图

    @Test
    fun `meta state maps to modifier bitmap`() {
        val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON
        val expected = HidModifier.LEFT_SHIFT or HidModifier.LEFT_CTRL or
            HidModifier.LEFT_ALT or HidModifier.LEFT_GUI
        assertEquals(expected, HidUsageMapper.modifiersFromMetaState(meta))
        assertEquals(0, HidUsageMapper.modifiersFromMetaState(0))
    }

    // ---------------------------------------------------------------- 媒体键

    @Test
    fun `media keys map to consumer page usages`() {
        assertEquals(HidUsage.CONSUMER_VOLUME_UP, HidUsageMapper.consumerUsageFor(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(HidUsage.CONSUMER_VOLUME_DOWN, HidUsageMapper.consumerUsageFor(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals(HidUsage.CONSUMER_MUTE, HidUsageMapper.consumerUsageFor(KeyEvent.KEYCODE_VOLUME_MUTE))
        assertEquals(HidUsage.CONSUMER_PLAY_PAUSE, HidUsageMapper.consumerUsageFor(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(HidUsage.CONSUMER_SCAN_NEXT_TRACK, HidUsageMapper.consumerUsageFor(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(HidUsage.CONSUMER_SCAN_PREVIOUS_TRACK, HidUsageMapper.consumerUsageFor(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(HidUsage.CONSUMER_STOP, HidUsageMapper.consumerUsageFor(KeyEvent.KEYCODE_MEDIA_STOP))
        assertEquals(HidUsage.CONSUMER_BRIGHTNESS_UP, HidUsageMapper.consumerUsageFor(KeyEvent.KEYCODE_BRIGHTNESS_UP))
        assertEquals(HidUsage.CONSUMER_BRIGHTNESS_DOWN, HidUsageMapper.consumerUsageFor(KeyEvent.KEYCODE_BRIGHTNESS_DOWN))
        assertNull(HidUsageMapper.consumerUsageFor(KeyEvent.KEYCODE_A))
    }

    @Test
    fun `media key names used by keyboard page are mapped`() {
        assertEquals(HidUsage.CONSUMER_VOLUME_UP, HidUsageMapper.consumerUsageForName("volume_up"))
        assertEquals(HidUsage.CONSUMER_VOLUME_DOWN, HidUsageMapper.consumerUsageForName("volume_down"))
        assertEquals(HidUsage.CONSUMER_MUTE, HidUsageMapper.consumerUsageForName("mute"))
        assertEquals(HidUsage.CONSUMER_PLAY_PAUSE, HidUsageMapper.consumerUsageForName("play_pause"))
        assertEquals(HidUsage.CONSUMER_SCAN_NEXT_TRACK, HidUsageMapper.consumerUsageForName("next_track"))
        assertEquals(HidUsage.CONSUMER_SCAN_PREVIOUS_TRACK, HidUsageMapper.consumerUsageForName("previous_track"))
        assertEquals(HidUsage.CONSUMER_BRIGHTNESS_UP, HidUsageMapper.consumerUsageForName("brightness_up"))
        assertEquals(HidUsage.CONSUMER_BRIGHTNESS_DOWN, HidUsageMapper.consumerUsageForName("brightness_down"))
    }

    @Test
    fun `consumer usage values match hid usage tables`() {
        assertEquals(0xE2, HidUsage.CONSUMER_MUTE)
        assertEquals(0xE9, HidUsage.CONSUMER_VOLUME_UP)
        assertEquals(0xEA, HidUsage.CONSUMER_VOLUME_DOWN)
        assertEquals(0xCD, HidUsage.CONSUMER_PLAY_PAUSE)
        assertEquals(0xB5, HidUsage.CONSUMER_SCAN_NEXT_TRACK)
        assertEquals(0xB6, HidUsage.CONSUMER_SCAN_PREVIOUS_TRACK)
        assertEquals(0x6F, HidUsage.CONSUMER_BRIGHTNESS_UP)
        assertEquals(0x70, HidUsage.CONSUMER_BRIGHTNESS_DOWN)
        assertEquals(0x238, HidUsage.CONSUMER_AC_PAN)
    }
}
