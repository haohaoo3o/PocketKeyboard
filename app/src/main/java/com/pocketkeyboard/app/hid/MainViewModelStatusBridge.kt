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
 *   `setActiveDevice` / `setConnectedDeviceAddresses`），不改契约字段；
 * - 平台判定线索（[HidDeviceInfo.platform]）在这里转成 UI 契约的
 *   [DevicePlatform]；无法判定时保守地给 [DevicePlatform.OTHER]，
 *   由配对页首次连接时的平台选择 Dialog 纠正并持久化。
 *
 * 本类同时实现 [PairingPinProvider]：配对码自动应答（Bug 2a）需要知道「App 当前展示的
 * 6 位配对码」，这个值的唯一来源就是 `MainViewModel.pinCode`，因此由 bridge 直接提供，
 * 避免 hid 层反向依赖 UI。
 */
class MainViewModelStatusBridge(
    private val viewModel: MainViewModel,
) : HidStatusListener, PairingPinProvider {

    private val _unavailableReason = MutableStateFlow<HidUnavailableReason?>(null)

    /** 最近一次「连接不可用」原因；UI 可据此展示中文提示。 */
    val unavailableReason: StateFlow<HidUnavailableReason?> = _unavailableReason.asStateFlow()

    override val pairingPinCode: String?
        get() = viewModel.pinCode.value

    override fun onAppRegistrationChanged(registered: Boolean) {
        // MainViewModel 契约里没有单独的注册状态字段，注册结果通过设备列表 /
        // 不可用原因间接体现；注册成功后清掉旧的不可用提示（badge 只反映当前状态）。
        if (registered) {
            _unavailableReason.value = null
            viewModel.setConnectingDeviceAddress(null)
            viewModel.setConnectFailedAddress(null)
        }
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
        // 连接尝试随之中断：清掉「连接中 / 连接失败」的行内提示，交给退避重试重新发起
        if (viewModel.connectingDeviceAddress.value == address) {
            viewModel.setConnectingDeviceAddress(null)
        }
        if (viewModel.connectFailedAddress.value == address) {
            viewModel.setConnectFailedAddress(null)
        }
    }

    override fun onHostConnecting(address: String) {
        // 新一轮主动连接开始：清掉上一次失败提示，进入「连接中」
        viewModel.setConnectFailedAddress(null)
        viewModel.setConnectingDeviceAddress(address)
    }

    override fun onHostConnectFailed(address: String) {
        // 命令没下发成功（UI 需要可见反馈）；底层仍会退避重试，
        // 下次重试经 onHostConnecting 重新进入「连接中」
        if (viewModel.connectingDeviceAddress.value == address) {
            viewModel.setConnectingDeviceAddress(null)
        }
        viewModel.setConnectFailedAddress(address)
    }

    override fun onConnectedDevicesChanged(addresses: Set<String>) {
        // Bug 2b：registry 里已连接的 HID 对端，配对页据此显示「已连接」标识
        viewModel.setConnectedDeviceAddresses(addresses)
        // 系统真值说已连接：对应的「连接中 / 连接失败」提示该退场了
        viewModel.connectingDeviceAddress.value
            ?.takeIf { it in addresses }
            ?.let { viewModel.setConnectingDeviceAddress(null) }
        viewModel.connectFailedAddress.value
            ?.takeIf { it in addresses }
            ?.let { viewModel.setConnectFailedAddress(null) }
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
        // 配对码展示策略（Bug 2a）：
        // - PASSKEY_ENTRY / PIN：SystemPairingResponder 用 App 配对码自动应答，被控端要输入
        //   的就是 App 屏幕上那个码，因此优先展示 App 配对码；App 侧没有码时才退回系统广播值；
        // - 数字比较 / Just Works：没有需要用户输入的数字，只展示系统广播带来的数字（没有则
        //   清空显示占位），确认由 App 自动完成，用户无需再点。
        val appPin = viewModel.pinCode.value?.takeIf { it.isNotBlank() }
        val systemPin = pinOrPasskey?.let { String.format(Locale.US, "%06d", it) }
        val display = when (variant) {
            HidPairingVariant.PIN, HidPairingVariant.PASSKEY_ENTRY -> appPin ?: systemPin
            else -> systemPin
        }
        viewModel.setPinCode(display)
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
