package com.pocketkeyboard.app.gesture

import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.PairedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 五指横滑在已配对设备间循环切换的纯 JVM 单测。 */
class DeviceCyclerTest {

    private val devices = listOf(
        PairedDevice("AA", "iPhone", DevicePlatform.APPLE),
        PairedDevice("BB", "iPad", DevicePlatform.APPLE),
        PairedDevice("CC", "MacBook", DevicePlatform.OTHER),
    )

    @Test
    fun `空列表返回 null`() {
        assertNull(cycleActiveDevice(emptyList(), null, SwipeDirection.RIGHT))
        assertNull(cycleActiveDevice(emptyList(), devices.first(), SwipeDirection.LEFT))
    }

    @Test
    fun `尚未选中设备时取第一台`() {
        assertEquals(devices.first(), cycleActiveDevice(devices, null, SwipeDirection.RIGHT))
        assertEquals(devices.first(), cycleActiveDevice(devices, null, SwipeDirection.LEFT))
    }

    @Test
    fun `右滑切到下一台`() {
        assertEquals(devices[1], cycleActiveDevice(devices, devices[0], SwipeDirection.RIGHT))
        assertEquals(devices[2], cycleActiveDevice(devices, devices[1], SwipeDirection.RIGHT))
    }

    @Test
    fun `右滑到末尾绕回第一台`() {
        assertEquals(devices[0], cycleActiveDevice(devices, devices[2], SwipeDirection.RIGHT))
    }

    @Test
    fun `左滑切到上一台`() {
        assertEquals(devices[0], cycleActiveDevice(devices, devices[1], SwipeDirection.LEFT))
    }

    @Test
    fun `左滑到开头绕回最后一台`() {
        assertEquals(devices[2], cycleActiveDevice(devices, devices[0], SwipeDirection.LEFT))
    }

    @Test
    fun `当前设备不在列表里时取第一台`() {
        val stranger = PairedDevice("ZZ", "其他", DevicePlatform.OTHER)
        assertEquals(devices.first(), cycleActiveDevice(devices, stranger, SwipeDirection.RIGHT))
        assertEquals(devices.first(), cycleActiveDevice(devices, stranger, SwipeDirection.LEFT))
    }

    @Test
    fun `只有一台设备时左右滑都停在它自己`() {
        val single = listOf(devices[1])
        assertEquals(single[0], cycleActiveDevice(single, single[0], SwipeDirection.RIGHT))
        assertEquals(single[0], cycleActiveDevice(single, single[0], SwipeDirection.LEFT))
    }

    @Test
    fun `循环索引归一化`() {
        // 从第 0 台往后退一步应绕到最后一台（索引 2）
        assertEquals(2, nextActiveDeviceIndex(0, 3, -1))
        assertEquals(2, nextActiveDeviceIndex(0, 3, -1 - 3))
        assertEquals(0, nextActiveDeviceIndex(2, 3, 1))
        assertEquals(0, nextActiveDeviceIndex(2, 3, 1 + 3))
        assertEquals(0, nextActiveDeviceIndex(-5, 3, 1))
    }
}
