package com.pocketkeyboard.app.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HidReportFactory] 单元测试（纯 JVM，无 Android 依赖）。 */
class HidReportFactoryTest {

    @Test
    fun `keyboard report is 8 bytes with modifier reserved and 6 keys`() {
        val report = HidReportFactory.keyboard(HidModifier.LEFT_CTRL or HidModifier.LEFT_SHIFT, byteArrayOf(0x04, 0x05))

        assertEquals(8, report.size)
        assertEquals(0x03.toByte(), report[0]) // LeftCtrl | LeftShift
        assertEquals(0x00.toByte(), report[1]) // Reserved
        assertEquals(0x04.toByte(), report[2])
        assertEquals(0x05.toByte(), report[3])
        assertEquals(0x00.toByte(), report[4])
        assertEquals(0x00.toByte(), report[5])
        assertEquals(0x00.toByte(), report[6])
        assertEquals(0x00.toByte(), report[7])
    }

    @Test
    fun `keyboard release report is all zero`() {
        val report = HidReportFactory.keyboardRelease()

        assertEquals(8, report.size)
        assertArrayEquals(ByteArray(8), report)
    }

    @Test
    fun `keyboard report drops duplicates and zero keys`() {
        val report = HidReportFactory.keyboard(0, byteArrayOf(0x04, 0x00, 0x04, 0x06))

        assertEquals(0x04.toByte(), report[2])
        assertEquals(0x06.toByte(), report[3])
        assertEquals(0x00.toByte(), report[4])
    }

    @Test
    fun `keyboard report keeps only first six distinct keys`() {
        val keys = byteArrayOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B)
        val report = HidReportFactory.keyboard(0, keys)

        val reported = report.drop(2).dropLast(0).take(6)
        assertEquals(listOf<Byte>(0x04, 0x05, 0x06, 0x07, 0x08, 0x09), reported)
        // 第 6 个键落在最后一个键位（index 7）；第 7 个（0x0A）被截断
        assertEquals(0x09.toByte(), report[7])
        assertFalse(report.drop(2).contains(0x0A.toByte()))
    }

    @Test
    fun `keyboard report masks modifiers to 8 bits`() {
        val report = HidReportFactory.keyboard(0x1FF, ByteArray(0))

        assertEquals(0xFF.toByte(), report[0])
    }

    @Test
    fun `consumer report is little endian 16 bit`() {
        assertArrayEquals(byteArrayOf(0xE2.toByte(), 0x00), HidReportFactory.consumer(HidUsage.CONSUMER_MUTE))
        assertArrayEquals(byteArrayOf(0xE9.toByte(), 0x00), HidReportFactory.consumer(HidUsage.CONSUMER_VOLUME_UP))
        assertArrayEquals(byteArrayOf(0x6F.toByte(), 0x00), HidReportFactory.consumer(HidUsage.CONSUMER_BRIGHTNESS_UP))
        assertArrayEquals(byteArrayOf(0x38, 0x02), HidReportFactory.consumer(HidUsage.CONSUMER_AC_PAN))
    }

    @Test
    fun `consumer release is zero usage`() {
        assertArrayEquals(byteArrayOf(0x00, 0x00), HidReportFactory.consumerRelease())
    }

    @Test
    fun `mouse report layout is buttons x y wheel`() {
        val report = HidReportFactory.mouse(HidReportFactory.BUTTON_LEFT, 10, -20, 3)

        assertEquals(4, report.size)
        assertEquals(0x01.toByte(), report[0])
        assertEquals(10.toByte(), report[1])
        assertEquals((-20).toByte(), report[2])
        assertEquals(3.toByte(), report[3])
    }

    @Test
    fun `mouse with pan appends horizontal wheel`() {
        val report = HidReportFactory.mouseWithPan(HidReportFactory.BUTTON_RIGHT, 1, 2, -3, 7)

        assertEquals(5, report.size)
        assertEquals(0x02.toByte(), report[0])
        assertEquals(1.toByte(), report[1])
        assertEquals(2.toByte(), report[2])
        assertEquals((-3).toByte(), report[3])
        assertEquals(7.toByte(), report[4])
    }

    @Test
    fun `axis values are clamped to signed byte range`() {
        assertEquals(127, HidReportFactory.clampAxis(1000))
        assertEquals(-127, HidReportFactory.clampAxis(-1000))
        assertEquals(0, HidReportFactory.clampAxis(0))
        assertEquals(126, HidReportFactory.clampAxis(126))
    }

    @Test
    fun `mouse button mask is clamped to three bits`() {
        // 3 bit = 左 / 右 / 中；中键当前接口未暴露，但位保留（描述符里声明了 Button 3），
        // 因此掩码是 0x07 而不是 0x03
        assertEquals(0x07, HidReportFactory.clampButtons(0xFF))
        assertEquals(
            HidReportFactory.BUTTON_LEFT or HidReportFactory.BUTTON_RIGHT,
            HidReportFactory.clampButtons(0x03),
        )
    }

    @Test
    fun `modifier bit mapping matches boot keyboard report spec`() {
        assertEquals(0x01, HidModifier.LEFT_CTRL)
        assertEquals(0x02, HidModifier.LEFT_SHIFT)
        assertEquals(0x04, HidModifier.LEFT_ALT)
        assertEquals(0x08, HidModifier.LEFT_GUI)
        assertEquals(0x10, HidModifier.RIGHT_CTRL)
        assertEquals(0x20, HidModifier.RIGHT_SHIFT)
        assertEquals(0x40, HidModifier.RIGHT_ALT)
        assertEquals(0x80, HidModifier.RIGHT_GUI)
        assertTrue(HidModifier.isModifier(HidUsage.KEY_LEFT_GUI))
        assertTrue(!HidModifier.isModifier(HidUsage.KEY_A))
        assertEquals(HidModifier.RIGHT_GUI, HidModifier.bitFor(HidUsage.KEY_RIGHT_GUI))
    }
}
