package com.pocketkeyboard.app.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HidReportDescriptor] 结构校验（纯 JVM）。 */
class HidReportDescriptorTest {

    private val bytes = HidReportDescriptor.bytes

    @Test
    fun `descriptor size stays well below bluetooth sdp limits`() {
        // 蓝牙 SDP 的 HIDDescriptorList 与各系统解析实现都对描述符长度敏感，
        // 复合键鼠保持在 512 字节以内最稳。
        assertTrue("descriptor too large: ${bytes.size}", bytes.size in 32..512)
    }

    @Test
    fun `descriptor starts with keyboard top level collection`() {
        val expected = bytes(
            0x05, 0x01, // Usage Page (Generic Desktop)
            0x09, 0x06, // Usage (Keyboard)
            0xA1, 0x01, // Collection (Application)
        )
        assertSubArrayAt(expected, 0)
    }

    @Test
    fun `all four report ids are declared in order`() {
        val reportIds = items().filter { it.header == 0x85.toByte() }
            .map { it.payload.single().toInt() and 0xFF }
        assertEquals(listOf(1, 2, 3, 5), reportIds)
    }

    @Test
    fun `collections are balanced`() {
        val collections = items().count { it.header == 0xA1.toByte() }
        val endCollections = items().count { it.header == 0xC0.toByte() }
        assertEquals(4, collections) // Keyboard + Consumer + Mouse + Pointer(Physical)
        assertEquals(4, endCollections)
    }

    @Test
    fun `usage pages are declared`() {
        val pages = items().filter { it.header == 0x05.toByte() }.map { it.payload.single().toInt() and 0xFF }
        assertTrue(pages.contains(HidPage.GENERIC_DESKTOP))
        assertTrue(pages.contains(HidPage.BUTTON))
        assertTrue(pages.contains(HidPage.KEYBOARD))
        assertTrue(pages.contains(HidPage.LEDS))
        assertTrue(pages.contains(HidPage.CONSUMER))
    }

    @Test
    fun `keyboard collection uses boot protocol layout`() {
        assertSubArrayAt(
            bytes(
                0x05, 0x07, // Usage Page (Keyboard/Keypad)
                0x19, 0xE0, // Usage Minimum (LeftControl)
                0x29, 0xE7, // Usage Maximum (RightGUI)
            ),
            indexOf(bytes(0x85, 0x01)) + 2,
        )
        assertSubArrayAt(
            bytes(
                0x15, 0x00, // Logical Minimum (0)
                0x25, 0x01, // Logical Maximum (1)
                0x75, 0x01, // Report Size (1)
                0x95, 0x08, // Report Count (8)
                0x81, 0x02, // Input (Data, Var, Abs) -> 8 modifier bits
                0x95, 0x01, // Report Count (1)
                0x75, 0x08, // Report Size (8)
                0x81, 0x01, // Input (Constant, Array, Abs) -> reserved byte
            ),
            // 锚点跳过 Usage Minimum/Maximum（19 E0 29 E7 共 4 字节），
            // 从紧随其后的 Logical Minimum 开始比对
            indexOf(bytes(0x19, 0xE0)) + 4,
        )
    }

    @Test
    fun `keyboard key array covers 87 keys plus numpad extensions`() {
        // 上限 0xFF：键盘/小键盘页全范围（含 Keypad +/- 0xD7 等小键盘扩展键）。
        // 曾经用 0xC7，导致 NUMPAD 布局的「+/-」既发不出去也解析不了。
        val index = indexOf(bytes(0x05, 0x07, 0x15, 0x00, 0x25, 0xFF))
        assertTrue("key array range 0x00-0xFF not found", index >= 0)
        assertSubArrayAt(
            bytes(
                0x19, 0x00, // Usage Minimum (Reserved / no event)
                0x29, 0xFF, // Usage Maximum (0xFF)
                0x75, 0x08, // Report Size (8)
                0x95, 0x06, // Report Count (6) -> 6-key rollover
                0x81, 0x00, // Input (Data, Array, Abs)
            ),
            // 锚点跳过刚匹配到的 6 字节前缀（Usage Page + Logical Min/Max），
            // 从紧随其后的 Usage Minimum 开始比对
            index + 6,
        )
    }

    @Test
    fun `led output report is declared`() {
        assertSubArrayAt(
            bytes(
                0x05, 0x08, // Usage Page (LEDs)
                0x19, 0x01, // Usage Minimum (Num Lock)
                0x29, 0x05, // Usage Maximum (Kana)
                0x75, 0x01, // Report Size (1)
                0x95, 0x05, // Report Count (5)
                0x91, 0x02, // Output (Data, Var, Abs)
            ),
            indexOf(bytes(0x05, 0x08)),
        )
    }

    @Test
    fun `consumer collection declares 16 bit array usage`() {
        assertSubArrayAt(
            bytes(
                0x05, 0x0C, // Usage Page (Consumer)
                0x09, 0x01, // Usage (Consumer Control)
                0xA1, 0x01, // Collection (Application)
                0x85, 0x02, // Report ID (2)
                0x15, 0x00, // Logical Minimum (0)
                0x26, 0xFF, 0x03, // Logical Maximum (0x03FF)：Global 项 2 字节载荷 → 头 0x26
                0x19, 0x00, // Usage Minimum (0)
                0x2A, 0xFF, 0x03, // Usage Maximum (0x03FF)：Local 项 2 字节载荷 → 头 0x2A
                0x75, 0x10, // Report Size (16)
                0x95, 0x01, // Report Count (1)
                0x81, 0x00, // Input (Data, Array, Abs)
                0xC0, // End Collection
            ),
            indexOf(bytes(0x05, 0x0C)),
        )
    }

    @Test
    fun `mouse collection declares buttons x y wheel and pan`() {
        assertSubArrayAt(
            bytes(
                0x05, 0x01, // Usage Page (Generic Desktop)
                0x09, 0x02, // Usage (Mouse)
                0xA1, 0x01, // Collection (Application)
                0x85, 0x03, // Report ID (3)
                0x09, 0x01, // Usage (Pointer)
                0xA1, 0x00, // Collection (Physical)
                0x05, 0x09, // Usage Page (Button)
                0x19, 0x01, // Usage Minimum (Button 1)
                0x29, 0x03, // Usage Maximum (Button 3)
                0x15, 0x00, // Logical Minimum (0)
                0x25, 0x01, // Logical Maximum (1)
                0x75, 0x01, // Report Size (1)
                0x95, 0x03, // Report Count (3)
                0x81, 0x02, // Input (Data, Var, Abs)
                0x95, 0x01, // Report Count (1)
                0x75, 0x05, // Report Size (5) -> padding
                0x81, 0x03, // Input (Constant, Var, Abs)
                0x05, 0x01, // Usage Page (Generic Desktop)
                0x09, 0x30, // Usage (X)
                0x09, 0x31, // Usage (Y)
                0x09, 0x38, // Usage (Wheel)
                0x15, 0x81, // Logical Minimum (-127)
                0x25, 0x7F, // Logical Maximum (127)
                0x75, 0x08, // Report Size (8)
                0x95, 0x03, // Report Count (3)
                0x81, 0x06, // Input (Data, Var, Relative)
                0xC0, // End Collection (Physical)
                0x85, 0x05, // Report ID (5)
                0x05, 0x0C, // Usage Page (Consumer)
                0x0A, 0x38, 0x02, // Usage (AC Pan = 0x0238)
                0x15, 0x81, // Logical Minimum (-127)
                0x25, 0x7F, // Logical Maximum (127)
                0x75, 0x08, // Report Size (8)
                0x95, 0x01, // Report Count (1)
                0x81, 0x06, // Input (Data, Var, Relative)
                0xC0, // End Collection (Mouse)
            ),
            indexOf(bytes(0x05, 0x01, 0x09, 0x02, 0xA1, 0x01)),
        )
    }

    @Test
    fun `descriptor ends after mouse collection`() {
        // Byte 是有符号的：0xC0 读出来是 -64，先转无符号再比
        assertEquals(0xC0, bytes[bytes.size - 1].toInt() and 0xFF)
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 把无符号十六进制字面量统一转成 ByteArray。
     *
     * Kotlin 只会把 0–127 的整数字面量直接当作 Byte，0x80 以上的值必须显式 `.toByte()`；
     * HID 描述符里大量出现 0x81/0x95/0xE0 这类值，用本辅助函数更清晰。
     */
    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    private data class Item(val header: Byte, val payload: List<Byte>)

    /** 按 short item 规则切分整个描述符。 */
    private fun items(): List<Item> {
        val result = mutableListOf<Item>()
        var index = 0
        while (index < bytes.size) {
            val header = bytes[index]
            val size = (header.toInt() and 0x03).let { if (it == 3) 4 else it }
            val payload = bytes.copyOfRange(index + 1, index + 1 + size).toList()
            result.add(Item(header, payload))
            index += 1 + size
        }
        assertEquals("descriptor fully parsed", bytes.size, index)
        return result
    }

    private fun indexOf(pattern: ByteArray): Int {
        outer@ for (start in 0..(bytes.size - pattern.size)) {
            for (offset in pattern.indices) {
                if (bytes[start + offset] != pattern[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    private fun assertSubArrayAt(expected: ByteArray, start: Int) {
        assertTrue("expected pattern not found at $start", start >= 0)
        assertTrue(
            "descriptor mismatch at $start",
            start + expected.size <= bytes.size,
        )
        for (offset in expected.indices) {
            assertEquals(
                "byte ${start + offset} mismatch",
                expected[offset],
                bytes[start + offset],
            )
        }
    }
}
