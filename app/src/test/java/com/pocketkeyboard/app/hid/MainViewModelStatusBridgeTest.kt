package com.pocketkeyboard.app.hid

import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.PairedDevice
import com.pocketkeyboard.app.ui.PairingDisplay
import com.pocketkeyboard.app.ui.PairingDisplayKind
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
    fun `pin variant shows the fixed app pin`() {
        bridge.onPairingRequest("AA:BB:CC:DD:EE:01", HidPairingVariant.PIN, pinOrPasskey = 999999)

        // 屏幕上的数字 = 实际配对用的数字：PIN 输入类永远是固定配对码 0000
        assertEquals(APP_PAIRING_PIN, viewModel.pinCode.value)
        assertEquals(
            PairingDisplay("AA:BB:CC:DD:EE:01", PairingDisplayKind.SHOW_APP_PIN),
            viewModel.pairingDisplay.value,
        )
    }

    @Test
    fun `passkey entry asks the user for the remote key`() {
        bridge.onPairingRequest("AA:BB:CC:DD:EE:01", HidPairingVariant.PASSKEY_ENTRY, pinOrPasskey = null)

        // 对端展示的数字必须由用户转述：展示输入指令而不是猜一个码
        assertNull(viewModel.pinCode.value)
        assertEquals(
            PairingDisplay("AA:BB:CC:DD:EE:01", PairingDisplayKind.NEED_REMOTE_KEY_INPUT),
            viewModel.pairingDisplay.value,
        )
    }

    @Test
    fun `confirmation variants show the live system key`() {
        bridge.onPairingRequest(
            "AA:BB:CC:DD:EE:01",
            HidPairingVariant.PASSKEY_CONFIRMATION,
            246810,
        )

        assertEquals("246810", viewModel.pinCode.value)
        assertEquals(
            PairingDisplay("AA:BB:CC:DD:EE:01", PairingDisplayKind.SHOW_SHARED_KEY),
            viewModel.pairingDisplay.value,
        )
    }

    @Test
    fun `display variant shows the live system key as entry key`() {
        bridge.onPairingRequest("AA:BB:CC:DD:EE:01", HidPairingVariant.DISPLAY, 123456)

        assertEquals("123456", viewModel.pinCode.value)
        assertEquals(
            PairingDisplay("AA:BB:CC:DD:EE:01", PairingDisplayKind.SHOW_ENTRY_KEY),
            viewModel.pairingDisplay.value,
        )
    }

    @Test
    fun `just works shows auto confirmed without digits`() {
        bridge.onPairingRequest("AA:BB:CC:DD:EE:01", HidPairingVariant.CONSENT, null)

        assertNull(viewModel.pinCode.value)
        assertEquals(
            PairingDisplay("AA:BB:CC:DD:EE:01", PairingDisplayKind.AUTO_CONFIRMED),
            viewModel.pairingDisplay.value,
        )
    }

    @Test
    fun `bond completion resets the pin block to idle`() {
        bridge.onPairingRequest("AA:BB:CC:DD:EE:01", HidPairingVariant.PASSKEY_CONFIRMATION, 246810)

        bridge.onBondStateChanged("AA:BB:CC:DD:EE:01", HidBondState.BONDED)

        // 回到空闲态：配对页展示固定配对码 0000（pinCode=null 时的 UI 兜底）
        assertNull(viewModel.pinCode.value)
        assertNull(viewModel.pairingDisplay.value)
    }

    @Test
    fun `auto answer success switches the hint to auto confirmed`() {
        bridge.onPairingRequest("AA:BB:CC:DD:EE:01", HidPairingVariant.PASSKEY_CONFIRMATION, 246810)

        bridge.onPairingAutoAnswered("AA:BB:CC:DD:EE:01", PairingAnswer.CONFIRMED)

        assertEquals(
            PairingDisplay("AA:BB:CC:DD:EE:01", PairingDisplayKind.AUTO_CONFIRMED),
            viewModel.pairingDisplay.value,
        )
    }

    @Test
    fun `auto answer failure keeps the manual confirm hint instead of lying`() {
        bridge.onPairingRequest("AA:BB:CC:DD:EE:01", HidPairingVariant.PASSKEY_CONFIRMATION, 246810)

        // 平台封锁（BLUETOOTH_PRIVILEGED）时应答会失败：不能谎称「已自动确认」
        bridge.onPairingAutoAnswered("AA:BB:CC:DD:EE:01", PairingAnswer.FAILED)

        assertEquals(
            PairingDisplay("AA:BB:CC:DD:EE:01", PairingDisplayKind.SHOW_SHARED_KEY),
            viewModel.pairingDisplay.value,
        )
    }

    @Test
    fun `host connected clears the connecting and failed flags for that host`() {
        viewModel.setConnectingDeviceAddress(ADDRESS)
        viewModel.setConnectFailedAddress(ADDRESS)

        bridge.onHostConnected(HidDeviceInfo(ADDRESS, "iPad", DevicePlatformHint.APPLE))

        assertNull(viewModel.connectingDeviceAddress.value)
        assertNull(viewModel.connectFailedAddress.value)
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

    // ------------------------------------------------------------ A2：连接中 / 连接失败桥接

    @Test
    fun `connecting sets the connecting address and clears a prior failure`() {
        viewModel.setConnectFailedAddress(ADDRESS)

        bridge.onHostConnecting(ADDRESS)

        assertEquals(ADDRESS, viewModel.connectingDeviceAddress.value)
        assertNull(viewModel.connectFailedAddress.value)
    }

    @Test
    fun `connect failed sets the failed address and clears connecting`() {
        viewModel.setConnectingDeviceAddress(ADDRESS)

        bridge.onHostConnectFailed(ADDRESS)

        assertNull(viewModel.connectingDeviceAddress.value)
        assertEquals(ADDRESS, viewModel.connectFailedAddress.value)
    }

    @Test
    fun `connected snapshot clears connecting and failed flags for that host`() {
        viewModel.setConnectingDeviceAddress(ADDRESS)
        viewModel.setConnectFailedAddress(OTHER_ADDRESS)

        bridge.onConnectedDevicesChanged(setOf(ADDRESS))

        assertNull(viewModel.connectingDeviceAddress.value)
        // 另一个host的失败提示与本 host 的连接无关，保持不动
        assertEquals(OTHER_ADDRESS, viewModel.connectFailedAddress.value)
    }

    @Test
    fun `disconnect clears the connecting flag for that host`() {
        viewModel.setConnectingDeviceAddress(ADDRESS)

        bridge.onHostDisconnected(ADDRESS)

        assertNull(viewModel.connectingDeviceAddress.value)
    }

    @Test
    fun `registration success clears the unavailable reason`() {
        bridge.onUnavailable(HidUnavailableReason.MISSING_PERMISSION)
        assertEquals(HidUnavailableReason.MISSING_PERMISSION, bridge.unavailableReason.value)

        bridge.onAppRegistrationChanged(registered = true)

        assertNull(bridge.unavailableReason.value)
    }

    @Test
    fun `unavailable reason survives a failed registration`() {
        bridge.onUnavailable(HidUnavailableReason.PROFILE_UNAVAILABLE)

        bridge.onAppRegistrationChanged(registered = false)

        // 注册失败的原因必须留着给 UI 显示
        assertEquals(HidUnavailableReason.PROFILE_UNAVAILABLE, bridge.unavailableReason.value)
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
        const val OTHER_ADDRESS = "AA:BB:CC:DD:EE:02"
    }
}
