package com.pocketkeyboard.app.keyboard

import com.pocketkeyboard.app.hid.HidModifier
import com.pocketkeyboard.app.hid.HidTransport
import com.pocketkeyboard.app.hid.HidUsage
import com.pocketkeyboard.app.hid.MouseButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeyboardReportEngine.keyTap]（竖屏融合页「系统输入法 → HID」转发路径）的报告序列
 * 单测（Bug 6）。
 *
 * 真机验证链路：IME 提交字符 → `HidUsageMapper` 查 usage → `keyTap` 发出
 * 「按下（修饰键位图 + usage）」+「松开（保留锁定的修饰键）」两份报告 → 被控设备。
 * 锁定修饰键（ctrl / shift / alt / win）与需要 Shift 的字符（! @ #）的组合
 * 在这些报告序列里逐位断言，不依赖真机。
 */
class KeyboardReportEngineTapTest {

    /** 记录报告序列的假传输（契约见 [HidTransport]）。 */
    private class RecordingTransport : HidTransport {
        data class KeyReport(val modifiers: Int, val keys: List<Int>)

        val reports = mutableListOf<KeyReport>()
        var consumerUsage: Int? = null

        override fun start() = Unit
        override fun stop() = Unit
        override fun sendKeyboardReport(modifiers: Int, keyCodes: ByteArray) {
            reports += KeyReport(modifiers, keyCodes.map { it.toInt() and 0xFF })
        }

        override fun sendConsumerUsage(usage: Int) {
            consumerUsage = usage
        }

        override fun sendMouseMove(dx: Int, dy: Int) = Unit
        override fun sendScroll(dx: Int, dy: Int) = Unit
        override fun sendMouseButton(button: MouseButton, pressed: Boolean) = Unit
    }

    /** 字符 → usage（与融合页 IME 转发路径同一映射：HidUsageMapper）。 */
    private fun usageOf(char: Char): Int =
        com.pocketkeyboard.app.hid.HidUsageMapper.usageForChar(char)!!.usage

    @Test
    fun `plain tap presses the usage then releases everything`() {
        val transport = RecordingTransport()
        KeyboardReportEngine(transport).keyTap(HidUsage.KEY_A)

        assertEquals(
            listOf(
                RecordingTransport.KeyReport(HidModifier.NONE, listOf(HidUsage.KEY_A)),
                RecordingTransport.KeyReport(HidModifier.NONE, emptyList()),
            ),
            transport.reports,
        )
    }

    @Test
    fun `locked ctrl plus c sends ctrl+c chord`() {
        val keyC = usageOf('c')
        val transport = RecordingTransport()
        KeyboardReportEngine(transport).keyTap(
            usage = keyC,
            extraModifiers = HidModifier.LEFT_CTRL,
        )

        assertEquals(HidModifier.LEFT_CTRL, transport.reports[0].modifiers)
        assertEquals(listOf(keyC), transport.reports[0].keys)
        // 松键报告仍保留 ctrl 位：修饰键处于锁定态，直到用户再次点击解锁
        assertEquals(HidModifier.LEFT_CTRL, transport.reports[1].modifiers)
        assertTrue(transport.reports[1].keys.isEmpty())
    }

    @Test
    fun `locked shift plus q sends shift+q and keeps shift on release`() {
        val keyQ = usageOf('q')
        val transport = RecordingTransport()
        KeyboardReportEngine(transport).keyTap(
            usage = keyQ,
            extraModifiers = HidModifier.LEFT_SHIFT,
        )

        assertEquals(HidModifier.LEFT_SHIFT, transport.reports[0].modifiers)
        assertEquals(listOf(keyQ), transport.reports[0].keys)
        assertEquals(HidModifier.LEFT_SHIFT, transport.reports[1].modifiers)
        assertTrue(transport.reports[1].keys.isEmpty())
    }

    @Test
    fun `multiple locked modifiers combine their bits`() {
        val transport = RecordingTransport()
        val bits = HidModifier.LEFT_CTRL or HidModifier.LEFT_SHIFT
        KeyboardReportEngine(transport).keyTap(HidUsage.KEY_A, extraModifiers = bits)

        assertEquals(bits, transport.reports[0].modifiers)
        assertEquals(listOf(HidUsage.KEY_A), transport.reports[0].keys)
        assertEquals(bits, transport.reports[1].modifiers)
    }

    @Test
    fun `momentary shift stacks on locked ctrl for shifted characters`() {
        // 锁定 ctrl 后打 '!'：usage = KEY_1，字符本身需要 Shift →
        // 按下报告 = ctrl | shift，松键报告只留 ctrl（momentary 一次即撤）
        val transport = RecordingTransport()
        KeyboardReportEngine(transport).keyTap(
            usage = HidUsage.KEY_1,
            extraModifiers = HidModifier.LEFT_CTRL,
            momentaryShift = true,
        )

        assertEquals(
            HidModifier.LEFT_CTRL or HidModifier.LEFT_SHIFT,
            transport.reports[0].modifiers,
        )
        assertEquals(listOf(HidUsage.KEY_1), transport.reports[0].keys)
        assertEquals(HidModifier.LEFT_CTRL, transport.reports[1].modifiers)
        assertTrue(transport.reports[1].keys.isEmpty())
    }

    @Test
    fun `engine modifier state stacks with tap modifiers`() {
        // 引擎内已按住的修饰键（keyboardDown 路径）与 keyTap 的叠加修饰键取并集：
        // 同一引擎的两个使用方（修饰键排 / IME 转发）互不影响位图
        val transport = RecordingTransport()
        val engine = KeyboardReportEngine(transport)
        engine.modifierDown(HidUsage.KEY_LEFT_ALT)
        engine.keyTap(HidUsage.KEY_A, extraModifiers = HidModifier.LEFT_CTRL)

        assertEquals(
            HidModifier.LEFT_ALT or HidModifier.LEFT_CTRL,
            transport.reports.last().modifiers,
        )
    }

    @Test
    fun `backspace tap with locked ctrl sends ctrl+backspace chord`() {
        // 融合页退格路径：文本缩短 → 补发退格 usage，锁定的修饰键一并叠加
        // （Ctrl+退格在 macOS / Windows 上都是「删词」语义）
        val transport = RecordingTransport()
        KeyboardReportEngine(transport).keyTap(
            usage = HidUsage.KEY_BACKSPACE,
            extraModifiers = HidModifier.LEFT_CTRL,
        )

        assertEquals(HidModifier.LEFT_CTRL, transport.reports[0].modifiers)
        assertEquals(listOf(HidUsage.KEY_BACKSPACE), transport.reports[0].keys)
    }

    @Test
    fun `releaseAll clears held modifier state`() {
        val transport = RecordingTransport()
        val engine = KeyboardReportEngine(transport)
        engine.modifierDown(HidUsage.KEY_LEFT_GUI)
        engine.keyTap(HidUsage.KEY_A, extraModifiers = HidModifier.LEFT_CTRL)
        engine.releaseAll()

        // releaseAll 发一份空修饰键 + 空按键的报告（页面退出时的松键兜底）
        val last = transport.reports.last()
        assertEquals(HidModifier.NONE, last.modifiers)
        assertTrue(last.keys.isEmpty())
    }
}
