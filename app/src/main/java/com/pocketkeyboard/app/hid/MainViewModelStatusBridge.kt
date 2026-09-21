package com.pocketkeyboard.app.hid

import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.PairedDevice
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 把 [HidStatusListener] 的状态桥接到 [MainViewModel]（本层与 UI 的唯一接触点）。
 *
 * 设计约束：
 * - 本层**不直接操作 UI**，也不持有 Activity / View；
 * - 只调用 `MainViewModel` 既有的 setter（`setPinCode` / `setPairedDevices` /
 *   `setActiveDevice`），不改契约字段；
 * - 平台判定线索（[HidDeviceInfo.platform]）在这里转成 UI 契约的
 *   [DevicePlatform]；无法判定时保守地给 [DevicePlatform.OTHER]，
 *   由配对页首次连接时的平台选择 Dialog 纠正并持久化。
 */
class MainViewModelStatusBridge(
    private val viewModel: MainViewModel,
) : HidStatusListener {

    private val _unavailableReason = MutableStateFlow<HidUnavailableReason?>(null)

    /** 最近一次「连接不可用」原因；UI 可据此展示中文提示。 */
    val unavailableReason: StateFlow<HidUnavailableReason?> = _unavailableReason.asStateFlow()

    override fun onAppRegistrationChanged(registered: Boolean) {
        // MainViewModel 契约里没有单独的注册状态字段，注册结果通过设备列表 /
        // 不可用原因间接体现；这里只做日志用途的保留钩子。
    }

    override fun onPairedDevicesChanged(devices: List<HidDeviceInfo>) {
        viewModel.setPairedDevices(devices.map { it.toPairedDevice() })
    }

    override fun onHostConnected(device: HidDeviceInfo) {
        val current = viewModel.pairedDevices.value
        if (current.none { it.address == device.address }) {
            viewModel.setPairedDevices(current + device.toPairedDevice())
        }
        // 不主动抢夺用户已选定的控制目标：只在还没有目标时把新连接的设备设为目标。
        if (viewModel.activeDevice.value == null) {
            viewModel.setActiveDevice(device.toPairedDevice())
        }
    }

    override fun onHostDisconnected(address: String) {
        val active = viewModel.activeDevice.value
        if (active != null && active.address == address) {
            val next = viewModel.pairedDevices.value.firstOrNull { it.address != address }
            viewModel.setActiveDevice(next)
        }
    }

    override fun onActiveDeviceChanged(device: HidDeviceInfo?) {
        viewModel.setActiveDevice(device?.toPairedDevice())
    }

    override fun onBondStateChanged(address: String, state: HidBondState) {
        // 配对流程结束（成功或被移除）后清掉配对码，避免残留。
        if (state == HidBondState.BONDED || state == HidBondState.NONE) {
            viewModel.setPinCode(null)
        }
    }

    override fun onPairingRequest(address: String, variant: HidPairingVariant, pinOrPasskey: Int?) {
        // 配对码兜底显示：系统配对对话框是 Android 上唯一可信来源，
        // 这里把系统广播里的 6 位数字原样转给 UI。
        // Just Works（CONSENT）没有数字，此时 pinCode 置空，UI 显示「等待确认」。
        viewModel.setPinCode(pinOrPasskey?.let { String.format(Locale.US, "%06d", it) })
    }

    override fun onUnavailable(reason: HidUnavailableReason) {
        _unavailableReason.value = reason
    }

    override fun onReconnecting(address: String, attempt: Int) {
        // 重连提示由 UI 自行拼接文案；这里无需改 ViewModel 状态。
    }

    override fun onLedStateChanged(numLock: Boolean, capsLock: Boolean, scrollLock: Boolean) {
        // 键盘页若需要显示 Caps Lock 状态，可在后续迭代里加到 MainViewModel。
    }

    private fun HidDeviceInfo.toPairedDevice(): PairedDevice = PairedDevice(
        address = address,
        name = name,
        platform = when (platform) {
            DevicePlatformHint.APPLE -> DevicePlatform.APPLE
            DevicePlatformHint.OTHER -> DevicePlatform.OTHER
            DevicePlatformHint.UNKNOWN -> DevicePlatform.OTHER
        },
    )
}
