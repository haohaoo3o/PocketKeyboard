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
}
