package com.pocketkeyboard.app.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 已配对设备（跨模块契约，禁止修改字段）。 */
data class PairedDevice(
    val address: String,
    val name: String,
    val platform: DevicePlatform,
)

/** 设备平台类型。 */
enum class DevicePlatform { APPLE, OTHER }

/** 应用页面模式。 */
enum class AppMode { PAIRING, KEYBOARD, TRACKPAD, NUMPAD }

/**
 * 全局共享 ViewModel（跨模块契约）。
 *
 * 由 MainActivity 持有，驱动页面切换；配对与传输逻辑由后续工程师填充，
 * 通过下面的 setter 更新状态即可。
 */
class MainViewModel : ViewModel() {

    private val _pinCode = MutableStateFlow<String?>(null)

    /** 配对码，null 表示无。 */
    val pinCode: StateFlow<String?> = _pinCode.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())

    /** 已配对设备列表。 */
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices.asStateFlow()

    private val _activeDevice = MutableStateFlow<PairedDevice?>(null)

    /** 当前控制目标设备，null 表示未连接。 */
    val activeDevice: StateFlow<PairedDevice?> = _activeDevice.asStateFlow()

    private val _mode = MutableStateFlow(AppMode.PAIRING)

    /** 当前页面模式：PAIRING / KEYBOARD / TRACKPAD / NUMPAD。 */
    val mode: StateFlow<AppMode> = _mode.asStateFlow()

    private val _connectedDeviceAddresses = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 当前已连接 HID 对端的地址集合（Bug 2b 新增字段，不动既有契约字段）。
     *
     * 数据来自 HID 后端的 registry 快照（`MainViewModelStatusBridge.onConnectedDevicesChanged`），
     * 配对页据此在设备行上显示「已连接」标识——「已配对」不等于「已连接」，
     * 用户需要能看出哪个设备真的在收发键鼠报告。
     */
    val connectedDeviceAddresses: StateFlow<Set<String>> = _connectedDeviceAddresses.asStateFlow()

    private val _connectingDeviceAddress = MutableStateFlow<String?>(null)

    /**
     * 正在主动连接（设备侧发起 HID L2CAP）的设备地址，null 表示没有进行中的连接（Bug A2）。
     *
     * 配对页据此把设备行切成「连接中…」：点设备行 / 注册后自动连接 / 退避重试都会经过
     * `MainViewModelStatusBridge.onHostConnecting` 置位，连接结果出来后清空。
     */
    val connectingDeviceAddress: StateFlow<String?> = _connectingDeviceAddress.asStateFlow()

    private val _connectFailedAddress = MutableStateFlow<String?>(null)

    /**
     * 最近一次主动连接**下发失败**（`BluetoothHidDevice.connect` 返回 false）的地址（Bug A2）。
     *
     * 底层仍会按退避策略重试（每次重试重新走 onHostConnecting 并清掉本状态）；
     * UI 据此短暂显示「连接失败」，避免用户以为点击没有反馈。
     */
    val connectFailedAddress: StateFlow<String?> = _connectFailedAddress.asStateFlow()

    // ---- 以下 setter 供配对 / 传输逻辑与页面调用 ----

    fun setPinCode(pinCode: String?) {
        _pinCode.value = pinCode
    }

    fun setPairedDevices(devices: List<PairedDevice>) {
        _pairedDevices.value = devices
    }

    fun setActiveDevice(device: PairedDevice?) {
        _activeDevice.value = device
    }

    fun setMode(mode: AppMode) {
        _mode.value = mode
    }

    /** 更新「已连接 HID 对端」地址集合（HID bridge 推送 registry 快照）。 */
    fun setConnectedDeviceAddresses(addresses: Set<String>) {
        _connectedDeviceAddresses.value = addresses
    }

    /** 置位 / 清空「正在主动连接」的设备地址（null = 无进行中的连接）。 */
    fun setConnectingDeviceAddress(address: String?) {
        _connectingDeviceAddress.value = address
    }

    /** 置位 / 清空「主动连接下发失败」的设备地址。 */
    fun setConnectFailedAddress(address: String?) {
        _connectFailedAddress.value = address
    }
}
