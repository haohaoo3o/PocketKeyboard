package com.pocketkeyboard.app.hid

import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.PairedDevice
import com.pocketkeyboard.app.ui.PairingDisplay
import com.pocketkeyboard.app.ui.PairingDisplayKind
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 把 [HidStatusListener] 的状态桥接到 [MainViewModel]（本层与 UI 的唯一接触点）。
 *
 * 设计约束：
 * - 本层**不直接操作 UI**，也不持有 Activity / View；
 * - 只调用 `MainViewModel` 既有的 setter（`setPinCode` / `setPairingDisplay` /
 *   `setPairedDevices` / `setActiveDevice` / `setConnectedDeviceAddresses`），
 *   不改契约字段（新增字段见 MainViewModel 内注释）；
 * - 平台判定线索（[HidDeviceInfo.platform]）在这里转成 UI 契约的
 *   [DevicePlatform]；无法判定时保守地给 [DevicePlatform.OTHER]，
 *   由配对页首次连接时的平台选择 Dialog 纠正并持久化。
 *
 * 配对码展示策略（配对接管）：`MainViewModel.pinCode` 存「当前展示的数字」，
 * [PairingDisplay] 告诉 UI 怎么展示 / 是否收集输入——页面上显示的永远等于实际配对用的：
 * - `PIN`：展示 App 固定配对码 [APP_PAIRING_PIN]（应答器 `setPin` 的就是它）；
 * - `PASSKEY_ENTRY`：展示输入框，用户转述对端屏幕上的数字（[PairingDisplayKind.NEED_REMOTE_KEY_INPUT]）；
 * - `PASSKEY_CONFIRMATION` / `DISPLAY`：展示系统当次生成的真实数字（EXTRA_PAIRING_KEY）；
 * - `CONSENT`：无数字，已自动确认。
 */
class MainViewModelStatusBridge(
    private val viewModel: MainViewModel,
) : HidStatusListener {

    private val _unavailableReason = MutableStateFlow<HidUnavailableReason?>(null)

    /** 最近一次「连接不可用」原因；UI 可据此展示中文提示。 */
    val unavailableReason: StateFlow<HidUnavailableReason?> = _unavailableReason.asStateFlow()

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
        // 连上了：该地址的「连接中 / 连接失败」行内提示就该退场（不止依赖快照回调，
        // 单点事件路径也要自洽，避免快照时序把「连接中」留在已连接的行上）
        if (viewModel.connectingDeviceAddress.value == device.address) {
            viewModel.setConnectingDeviceAddress(null)
        }
        if (viewModel.connectFailedAddress.value == device.address) {
            viewModel.setConnectFailedAddress(null)
        }
        // 不主动抢夺用户已选定的控制目标：只在还没有目标时把新连接的设备设为目标。
        if (viewModel.activeDevice.value == null) {
            viewModel.setActiveDevice(device.toPairedDevice())
        }
    }

    override fun onHostDisconnected(address: String) {
        val active = viewModel.activeDevice.value
        if (active != null && active.address == address) {
            // 只清空，**不**把另一台「已配对但未必已连接」的设备顶上来——那会让首页
            // 在没有任何连接时也显示着设备名（用户看到的「已连接」假象之一）。
            // 目标让位由传输层 registry 决定，经 onActiveDeviceChanged 回写。
            viewModel.setActiveDevice(null)
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
        // 配对流程结束（成功或被移除）后回到空闲态：展示固定配对码 0000
        if (state == HidBondState.BONDED || state == HidBondState.NONE) {
            viewModel.setPinCode(null)
            viewModel.setPairingDisplay(null)
        }
    }

    override fun onPairingRequest(address: String, variant: HidPairingVariant, pinOrPasskey: Int?) {
        // 配对码展示策略（配对接管）：屏幕上的数字 = 实际配对用的数字
        val systemKey = pinOrPasskey?.let { String.format(Locale.US, "%06d", it) }
        when (variant) {
            // PIN 输入类：应答器 setPin(0000)，被控端也输入 App 展示的 0000
            HidPairingVariant.PIN -> {
                viewModel.setPinCode(APP_PAIRING_PIN)
                viewModel.setPairingDisplay(PairingDisplay(address, PairingDisplayKind.SHOW_APP_PIN))
            }
            // 对端展示数字、本端输入：展示输入框收集用户转述的数字
            HidPairingVariant.PASSKEY_ENTRY -> {
                viewModel.setPinCode(null)
                viewModel.setPairingDisplay(
                    PairingDisplay(address, PairingDisplayKind.NEED_REMOTE_KEY_INPUT),
                )
            }
            // 数字比较：两边同一数字（系统生成），App 自动确认并展示供核对
            HidPairingVariant.PASSKEY_CONFIRMATION -> {
                viewModel.setPinCode(systemKey)
                viewModel.setPairingDisplay(PairingDisplay(address, PairingDisplayKind.SHOW_SHARED_KEY))
            }
            // 本端展示类：数字由系统生成、用户在被控端输入，App 只负责展示
            HidPairingVariant.DISPLAY -> {
                viewModel.setPinCode(systemKey)
                viewModel.setPairingDisplay(PairingDisplay(address, PairingDisplayKind.SHOW_ENTRY_KEY))
            }
            // Just Works：没有数字，展示已自动确认
            HidPairingVariant.CONSENT -> {
                viewModel.setPinCode(null)
                viewModel.setPairingDisplay(PairingDisplay(address, PairingDisplayKind.AUTO_CONFIRMED))
            }
            // 带外 / 未知：不接管展示，留系统配对框
            HidPairingVariant.OOB, HidPairingVariant.UNKNOWN -> {
                viewModel.setPinCode(null)
                viewModel.setPairingDisplay(null)
            }
        }
    }

    override fun onPairingAutoAnswered(address: String, answer: PairingAnswer) {
        // 应答真的成功了才说「已自动确认」；被平台封锁（BLUETOOTH_PRIVILEGED）时
        // 提示保持引导系统配对框的文案，绝不谎报
        if (answer == PairingAnswer.CONFIRMED) {
            viewModel.setPairingDisplay(PairingDisplay(address, PairingDisplayKind.AUTO_CONFIRMED))
        }
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
