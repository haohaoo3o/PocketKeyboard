package com.pocketkeyboard.app.hid

import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.PairedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MainViewModelStatusBridge] 单元测试：HID 状态 → MainViewModel 的转译。
 *
 * 覆盖本轮两个 bug 的桥接语义：
 * - Bug 2a：配对码展示以 App 配对码为准（自动应答用的就是它）；
 * - Bug 2b：已连接 HID 对端集合转发到 ViewModel（配对页「已连接」标识的数据源）。
 */
class MainViewModelStatusBridgeTest {

    private val viewModel = MainViewModel()
    private val bridge = MainViewModelStatusBridge(viewModel)

    @Test
    fun `app pin wins for passkey entry variants`() {
        viewModel.setPinCode("123456")

        bridge.onPairingRequest(
            "AA:BB:CC:DD:EE:01",
            HidPairingVariant.PASSKEY_ENTRY,
            pinOrPasskey = null,
        )

        assertEquals("123456", viewModel.pinCode.value)
    }

    @Test
    fun `app pin wins for pin variants even when broadcast carries a key`() {
        viewModel.setPinCode("123456")

        bridge.onPairingRequest("AA:BB:CC:DD:EE:01", HidPairingVariant.PIN, pinOrPasskey = 999999)

        assertEquals("123456", viewModel.pinCode.value)
    }

    @Test
    fun `system key is fallback when app has no pin`() {
        viewModel.setPinCode(null)

        bridge.onPairingRequest("AA:BB:CC:DD:EE:01", HidPairingVariant.PASSKEY_ENTRY, 246810)

        assertEquals("246810", viewModel.pinCode.value)
    }

    @Test
    fun `confirmation variants show the system key`() {
        viewModel.setPinCode("123456")

        bridge.onPairingRequest(
            "AA:BB:CC:DD:EE:01",
            HidPairingVariant.PASSKEY_CONFIRMATION,
            246810,
        )

        assertEquals("246810", viewModel.pinCode.value)
    }

    @Test
    fun `just works without key clears the pin`() {
        viewModel.setPinCode("123456")

        bridge.onPairingRequest("AA:BB:CC:DD:EE:01", HidPairingVariant.CONSENT, null)

        assertNull(viewModel.pinCode.value)
    }

    @Test
    fun `bridge exposes the app pin to the pairing responder`() {
        viewModel.setPinCode("424242")

        assertEquals("424242", bridge.pairingPinCode)
    }

    @Test
    fun `connected hosts snapshot reaches the view model`() {
        bridge.onConnectedDevicesChanged(setOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"))

        assertEquals(
            setOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
            viewModel.connectedDeviceAddresses.value,
        )

        bridge.onConnectedDevicesChanged(emptySet())

        assertEquals(emptySet<String>(), viewModel.connectedDeviceAddresses.value)
    }

    @Test
    fun `paired devices map apple hint to apple platform`() {
        bridge.onPairedDevicesChanged(
            listOf(
                HidDeviceInfo("AA:BB:CC:DD:EE:01", "iPhone", DevicePlatformHint.APPLE),
                HidDeviceInfo("AA:BB:CC:DD:EE:02", "ThinkPad", DevicePlatformHint.OTHER),
                HidDeviceInfo("AA:BB:CC:DD:EE:03", "???", DevicePlatformHint.UNKNOWN),
            ),
        )

        assertEquals(
            listOf(
                PairedDevice("AA:BB:CC:DD:EE:01", "iPhone", DevicePlatform.APPLE),
                PairedDevice("AA:BB:CC:DD:EE:02", "ThinkPad", DevicePlatform.OTHER),
                PairedDevice("AA:BB:CC:DD:EE:03", "???", DevicePlatform.OTHER),
            ),
            viewModel.pairedDevices.value,
        )
    }
}
